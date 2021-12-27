package io.mosip.admin.bulkdataupload.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.admin.bulkdataupload.dto.*;
import io.mosip.admin.packetstatusupdater.constant.ApiName;
import io.mosip.admin.packetstatusupdater.util.RestClient;
import io.mosip.commons.packet.facade.PacketReader;
import io.mosip.commons.packet.spi.IPacketCryptoService;
import io.mosip.kernel.core.http.ResponseWrapper;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.FileUtils;
import io.mosip.kernel.core.util.HMACUtils2;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static io.mosip.kernel.core.util.JsonUtils.javaObjectToJsonString;

@Component
public class PacketUploadService {

    private static final Logger logger = LoggerFactory.getLogger(PacketUploadService.class);
    private static final String PACKET_SYNC_STATUS_ID = "mosip.registration.sync";
    private static final String PACKET_SYNC_VERSION = "1.0";
    private static final String SPACE = " ";

    @Autowired
    private RestClient restClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("OnlinePacketCryptoServiceImpl")
    private IPacketCryptoService onlineCrypto;

    @Autowired
    private PacketReader packetReader;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${object.store.base.location:home}")
    private String baseLocation;

    @Value("${packet.manager.account.name:PACKET_MANAGER_ACCOUNT}")
    private String account;

    @Value("${mosip.admin.packetupload.packetsync.name}")
    private String nameFieldNames;

    @Value("${mosip.admin.packetupload.packetsync.email}")
    private String emailFieldName;

    @Value("${mosip.admin.packetupload.packetsync.phone}")
    private String phoneFieldName;

    @Value("${mosip.admin.packetupload.packetsync.url}")
    private String packetSyncURL;

    @Value("${mosip.kernel.packet-reciever-api-url}")
    private String packetReceiverURL;

    @Value("${mosip.mandatory-languages}")
    private String mandatoryLanguages;

    @Value("${mosip.optional-languages}")
    private String optionalLanguages;

    private String language;

    @PostConstruct
    public void init() {
        File accountLoc = new File(baseLocation + File.separator + account);
        if(!accountLoc.exists()) {
            logger.info("Creating object store location as it doesn't exists", accountLoc);
            accountLoc.mkdirs();
        }

        language = mandatoryLanguages != null ? mandatoryLanguages.split(",")[0].trim() :
                optionalLanguages.split(",")[0].trim();
    }


    public List<MachineRegistrationCenterDto> getMachineList(String centerId) {
        try {//TODO - take care of pagination
            ResponseWrapper<PageDto<MachineRegistrationCenterDto>> response = restClient.getApi(ApiName.MACHINE_GET_API,
                    Collections.singletonList(centerId),null,null, ResponseWrapper.class);
            if(response.getResponse() != null) {
                PageDto<MachineRegistrationCenterDto> pageDtoWrapper = objectMapper.readValue(objectMapper.writeValueAsString(response.getResponse()),
                        new TypeReference<PageDto<MachineRegistrationCenterDto>>() {});
                return objectMapper.readValue(objectMapper.writeValueAsString(pageDtoWrapper.getData()),
                        new TypeReference<List<MachineRegistrationCenterDto>>() {});
            }

            if(response.getErrors() != null && !response.getErrors().isEmpty()) {
                logger.error("Failed to fetch machines mapped to center : {} {} {}", centerId,
                        response.getErrors().get(0).getErrorCode(), response.getErrors().get(0).getMessage());
            }

        } catch (Exception e) {
            logger.error("Failed to fetch machines mapped to center : {}", centerId, e);
        }
        return Collections.EMPTY_LIST;
    }

    public PacketUploadStatus syncAndUploadPacket(MultipartFile file, String centerId, String supervisorStatus,
                                    String source, String process) throws JSONException {
        String[] nameFields = nameFieldNames.split(",");
        List<String> additionalInfoFields = new ArrayList<>();
        additionalInfoFields.addAll(List.of(nameFields));
        additionalInfoFields.add(phoneFieldName);
        additionalInfoFields.add(emailFieldName);

        List<MachineRegistrationCenterDto> machineList = getMachineList(centerId);
        logger.info("Fetched machines : {} for centerId : {}", machineList.size(), centerId);

        if(machineList.isEmpty())
            return new PacketUploadStatus("No machines found for the provided centerId", true);

        ResponseEntity<String> responseEntity = syncRegistration(centerId, source, process, file, supervisorStatus,
                additionalInfoFields, machineList);

        if(responseEntity != null && responseEntity.hasBody()) {
            JSONObject response = new JSONObject(responseEntity.getBody());
            if(response.get("response") != null && !(response.get("response") == JSONObject.NULL)) {
                logger.info("{} RID Sync is successful with response : {}", file.getOriginalFilename(), response.get("response"));
                responseEntity = uploadPacket(file);
                if(responseEntity != null && responseEntity.hasBody()) {
                    response = new JSONObject(responseEntity.getBody());
                    return getUploadStatus(response);
                }
            }
            else
                return getUploadStatus(response);
        }
        return new PacketUploadStatus("UNKNOWN ERROR : Empty Response", true);
    }


    private PacketUploadStatus getUploadStatus(JSONObject response) throws JSONException {
        if(!(response.get("errors") == JSONObject.NULL)) {
            return new PacketUploadStatus(response.getJSONArray("errors").get(0).toString(), true);
        }

        if(!(response.get("response") == JSONObject.NULL))
            return new PacketUploadStatus(response.getString("response"), false);

        return new PacketUploadStatus("UNKNOWN ERROR : Empty Response", true);
    }

    public ResponseEntity<String> syncRegistration(String centerId, String source, String process, MultipartFile file, String supervisorStatus,
                                 List<String> additionalInfoFields, List<MachineRegistrationCenterDto> machineList) {
        String containerName = file.getOriginalFilename().replace(".zip", "");
        String id = containerName.split("-")[0];

        for(MachineRegistrationCenterDto m : machineList) {
            String refId = centerId + "_" + m.getId();
            File packet = new File(baseLocation + File.separator + account + File.separator + id + ".zip");
            try {
                logger.info("Iterating RefId : {} with additionalInfoFields : {}", refId, additionalInfoFields);
                FileUtils.writeByteArrayToFile(packet, onlineCrypto.decrypt(refId, file.getBytes()));
                Map<String, String> additionalInfoFieldValues = packetReader.getFields(id, additionalInfoFields, source, process, true);

                List<String> fullName = new ArrayList<>();
                for(String field : nameFieldNames.split(",")) {
                    if(additionalInfoFieldValues.get(field) != null) {
                        List<ValueDto> valueDtos = objectMapper.readValue(additionalInfoFieldValues.get(field), new TypeReference<List<ValueDto>>() {});
                        Optional<ValueDto> valueDto = valueDtos.stream().filter( v -> language.equals(v.getLanguage())).findFirst();
                        if(valueDto.isPresent())
                            fullName.add(valueDto.get().getValue());
                    }
                }
                fullName.removeIf(Objects::isNull);

                RIDSyncDto syncdto = new RIDSyncDto();
                syncdto.setRegistrationId(id);
                syncdto.setRegistrationType(process);
                syncdto.setPacketId(containerName);
                syncdto.setPacketHashValue(HMACUtils2.digestAsPlainText(file.getBytes()));
                syncdto.setPacketSize(BigInteger.valueOf(file.getBytes().length));
                syncdto.setSupervisorStatus(supervisorStatus);
                syncdto.setName(String.join(SPACE, fullName));
                syncdto.setPhone(additionalInfoFieldValues.get(phoneFieldName));
                syncdto.setEmail(additionalInfoFieldValues.get(emailFieldName));
                syncdto.setLangCode(language);

                RegistrationPacketSyncDTO registrationPacketSyncDTO = new RegistrationPacketSyncDTO();
                registrationPacketSyncDTO
                        .setRequesttime(DateUtils.formatToISOString(DateUtils.getUTCCurrentDateTime()));
                registrationPacketSyncDTO.setRequest(Collections.singletonList(syncdto));
                registrationPacketSyncDTO.setId(PACKET_SYNC_STATUS_ID);
                registrationPacketSyncDTO.setVersion(PACKET_SYNC_VERSION);

                String encodedData = CryptoUtil.encodeToURLSafeBase64(onlineCrypto.encrypt(refId,
                        javaObjectToJsonString(registrationPacketSyncDTO).getBytes()));

                UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromUriString(packetSyncURL);
                URI uri = uriComponentsBuilder.build().toUri();
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.setContentType(MediaType.APPLICATION_JSON_UTF8);
                httpHeaders.add("timestamp", DateUtils.formatToISOString(LocalDateTime.now()));
                httpHeaders.add("Center-Machine-RefId", refId);
                HttpEntity<String> httpEntity = new HttpEntity<String>(javaObjectToJsonString(encodedData), httpHeaders);
                return restTemplate.exchange(uri, HttpMethod.POST, httpEntity, String.class);

            } catch (Throwable t) {
                logger.error("Failed to sync packet : {}", containerName, t);
            } finally {
                if(!FileUtils.deleteQuietly(packet)) { logger.warn("FAILED TO DELETE PACKET AFTER RID SYNC OPERATION"); }
            }
        }
        return null;
    }

    public ResponseEntity<String> uploadPacket(MultipartFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, String> fileMap = new LinkedMultiValueMap<>();
        ContentDisposition contentDisposition = ContentDisposition.builder("form-data").name("file")
                .filename(file.getOriginalFilename()).build();
        fileMap.add(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());
        try {
            HttpEntity<byte[]> fileEntity = new HttpEntity<>(file.getBytes(), fileMap);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileEntity);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            return restTemplate.exchange(packetReceiverURL, HttpMethod.POST,
                    requestEntity, String.class);

        } catch (Exception e) {
            logger.error("Failed to upload packet : {}", file.getOriginalFilename(), e);
        }
        return null;
    }

}
