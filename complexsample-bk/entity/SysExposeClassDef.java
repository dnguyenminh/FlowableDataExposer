package vn.com.fecredit.complexsample.entity;

import jakarta.persistence.*;
import vn.com.fecredit.complexsample.service.metadata.MetadataDefinition;

import java.time.OffsetDateTime;

/**
 * Entity representing an admin-managed metadata override stored in
 * <code>sys_expose_class_def</code>. The JSON payload contains a
 * {@link MetadataDefinition}.
 */
@Entity
@Table(name = "sys_expose_class_def")
public class SysExposeClassDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_name", nullable = false, length = 512)
    private String className;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Lob
    @Column(name = "json_definition", nullable = false, columnDefinition = "CLOB")
    private String jsonDefinition;

    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getJsonDefinition() { return jsonDefinition; }
    public void setJsonDefinition(String jsonDefinition) { this.jsonDefinition = jsonDefinition; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
