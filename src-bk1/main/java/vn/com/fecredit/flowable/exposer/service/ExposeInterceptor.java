package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.entity.SysCaseDataStore;
import vn.com.fecredit.flowable.exposer.repository.SysCaseDataStoreRepository;

import javax.crypto.SecretKey;
import java.util.Map;

@Component
public class ExposeInterceptor {

    private final SysCaseDataStoreRepository storeRepo;
    private final KeyManagementService keyService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExposeInterceptor(SysCaseDataStoreRepository storeRepo, KeyManagementService keyService) {
        this.storeRepo = storeRepo;
        this.keyService = keyService;
    }

    public SysCaseDataStore persistCase(String caseInstanceId, String entityType, Map<String, Object> variables) throws Exception {
        byte[] plain = mapper.writeValueAsBytes(variables);
        SecretKey dataKey = keyService.generateDataKey();
        String encryptedPayloadB64 = keyService.encryptWithDataKey(plain, dataKey);
        String wrappedKeyB64 = keyService.wrapDataKey(dataKey);

        SysCaseDataStore s = new SysCaseDataStore();
        s.setCaseInstanceId(caseInstanceId);
        s.setEntityType(entityType);
        s.setPayload(encryptedPayloadB64);
        s.setEncryptedKey(wrappedKeyB64);
        return storeRepo.save(s);
    }
}
