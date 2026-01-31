package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.flowable.exposer.entity.CasePlainOrder;
import vn.com.fecredit.flowable.exposer.entity.IdxReport;
import vn.com.fecredit.flowable.exposer.entity.SysCaseDataStore;
import vn.com.fecredit.flowable.exposer.repository.CasePlainOrderRepository;
import vn.com.fecredit.flowable.exposer.repository.IdxReportRepository;
import vn.com.fecredit.flowable.exposer.repository.SysCaseDataStoreRepository;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import javax.crypto.SecretKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

@Component
public class CaseDataWorker {

    private final SysCaseDataStoreRepository storeRepo;
    private final IdxReportRepository idxRepo;
    private final CasePlainOrderRepository plainRepo;
    private final KeyManagementService keyService;
    private final MetadataResolver metadataResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaseDataWorker(SysCaseDataStoreRepository storeRepo,
                          IdxReportRepository idxRepo,
                          CasePlainOrderRepository plainRepo,
                          KeyManagementService keyService,
                          MetadataResolver metadataResolver) {
        this.storeRepo = storeRepo;
        this.idxRepo = idxRepo;
        this.plainRepo = plainRepo;
        this.keyService = keyService;
        this.metadataResolver = metadataResolver;
    }

    // Asynchronous processing using virtual threads via ExecutorService (demo)
    public void processAsync(SysCaseDataStore record) {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try { process(record); } catch (Exception ignored) {}
        });
    }

    @Transactional
    public void process(SysCaseDataStore record) throws Exception {
        SecretKey dataKey = keyService.unwrapDataKey(record.getEncryptedKey());
        byte[] plain = keyService.decryptWithDataKey(record.getPayload(), dataKey);
        String jsonStr = new String(plain, java.nio.charset.StandardCharsets.UTF_8);

        Map<String, String> mappings = metadataResolver.mappingsFor(record.getEntityType());
        IdxReport idx = idxRepo.findByCaseInstanceId(record.getCaseInstanceId()).orElseGet(IdxReport::new);
        idx.setCaseInstanceId(record.getCaseInstanceId());

        // prepare plain exports
        Map<String, Object> plainExports = new HashMap<>();
        Map<String, MetadataDefinition.FieldMapping> meta = metadataResolver.mappingsMetadataFor(record.getEntityType());

        for (Map.Entry<String, String> e : mappings.entrySet()) {
            String column = e.getKey();
            String path = e.getValue();
            Object val = JsonPath.read(jsonStr, path);
            // index mapping (existing behaviour)
            if ("total_amount".equals(column) && val instanceof Number) {
                idx.setTotalAmount(((Number) val).doubleValue());
            } else if ("item_1_id".equals(column)) {
                idx.setItem1Id(val == null ? null : String.valueOf(val));
            } else if ("color_attr".equals(column)) {
                idx.setColorAttr(val == null ? null : String.valueOf(val));
            }

            // plain exports
            var fm = meta.get(column);
            if (fm != null && Boolean.TRUE.equals(fm.exportToPlain)) {
                String plainCol = fm.plainColumn != null ? fm.plainColumn : column;
                plainExports.put(plainCol, val);
                // also set known typed columns for case_plain_order
                if ("order_total".equals(plainCol) && val instanceof Number) {
                    // handled below when saving CasePlainOrder
                }
            }
        }

        idxRepo.save(idx);

        // save plain table if any
        if (!plainExports.isEmpty()) {
            // upsert into case_plain_order (example implementation for Order entity)
            plainRepo.upsertByCaseInstanceId(record.getCaseInstanceId(), ent -> {
                // set known columns
                if (plainExports.containsKey("order_total")) {
                    Object v = plainExports.get("order_total");
                    if (v instanceof Number) ent.setOrderTotal(((Number) v).doubleValue());
                }
                if (plainExports.containsKey("customer_id")) {
                    Object v = plainExports.get("customer_id");
                    ent.setCustomerId(v == null ? null : String.valueOf(v));
                }
                if (plainExports.containsKey("customer_name")) {
                    Object v = plainExports.get("customer_name");
                    ent.setCustomerName(v == null ? null : String.valueOf(v));
                }
                if (plainExports.containsKey("order_priority")) {
                    Object v = plainExports.get("order_priority");
                    ent.setOrderPriority(v == null ? null : String.valueOf(v));
                }
                if (plainExports.containsKey("approval_status")) {
                    Object v = plainExports.get("approval_status");
                    ent.setApprovalStatus(v == null ? null : String.valueOf(v));
                }
                if (plainExports.containsKey("decision_reason")) {
                    Object v = plainExports.get("decision_reason");
                    ent.setDecisionReason(v == null ? null : String.valueOf(v));
                }
                try {
                    ent.setPlainPayload(mapper.writeValueAsString(plainExports));
                    ent.setUpdatedAt(java.time.OffsetDateTime.now());
                } catch (Exception ex) {
                    // ignore payload serialization failure for now
                }
            }, plainRepo);
        }
    }

    @Transactional
    public void reindexAll(String entityType) throws Exception {
        List<SysCaseDataStore> rows = storeRepo.findByEntityType(entityType);
        // delete all index rows for this type (demo uses case id delete)
        rows.forEach(r -> idxRepo.deleteByCaseInstanceId(r.getCaseInstanceId()));
        for (SysCaseDataStore r : rows) {
            process(r);
        }
    }
}
