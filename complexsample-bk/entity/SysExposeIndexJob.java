package vn.com.fecredit.complexsample.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents an index/backfill job driven by metadata mappings.
 * mappings: stored as JSON string (array of mapping objects).
 * ddlStatements: stored as newline-separated SQL or JSON array string.
 */
@Entity
@Table(name = "sys_expose_index_job")
public class SysExposeIndexJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "class_name")
    private String className;

    @Column(name = "mappings", columnDefinition = "jsonb", nullable = false)
    private String mappings; // JSON array as string

    @Column(name = "ddl_statements", columnDefinition = "text[]")
    private String[] ddlStatements;

    @Column(name = "dry_run")
    private Boolean dryRun = Boolean.TRUE;

    @Column(name = "chunk_size")
    private Integer chunkSize = 1000;

    @Column(name = "status")
    private String status = "PENDING";

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getMappings() { return mappings; }
    public void setMappings(String mappings) { this.mappings = mappings; }
    public String[] getDdlStatements() { return ddlStatements; }
    public void setDdlStatements(String[] ddlStatements) { this.ddlStatements = ddlStatements; }
    public Boolean getDryRun() { return dryRun; }
    public void setDryRun(Boolean dryRun) { this.dryRun = dryRun; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(OffsetDateTime requestedAt) { this.requestedAt = requestedAt; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(OffsetDateTime processedAt) { this.processedAt = processedAt; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
