package vn.com.fecredit.flowable.exposer.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sys_case_data_store")
public class SysCaseDataStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_instance_id", nullable = false)
    private String caseInstanceId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "CLOB")
    private String payload;

    @Lob
    @Column(name = "encrypted_key", nullable = false, columnDefinition = "CLOB")
    private String encryptedKey;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCaseInstanceId() { return caseInstanceId; }
    public void setCaseInstanceId(String caseInstanceId) { this.caseInstanceId = caseInstanceId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getEncryptedKey() { return encryptedKey; }
    public void setEncryptedKey(String encryptedKey) { this.encryptedKey = encryptedKey; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
