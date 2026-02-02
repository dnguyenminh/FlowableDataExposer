package vn.com.fecredit.flowable.exposer.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * A normalized, denormalized 'plain' view of an Order stored for fast queries
 * and reporting (backed by the database table `case_plain_order`).
 *
 * <p>This entity is written by {@code CaseDataWorker} when a metadata mapping
 * requests `exportToPlain:true`. It contains a small number of commonly
 * queried fields (totals, customer id/name, priority, approval status) and
 * a JSON CLOB snapshot in `plainPayload` for ad-hoc inspection.</p>
 */
@Entity
@Table(name = "case_plain_order")
public class CasePlainOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_instance_id", nullable = false)
    private String caseInstanceId;

    @Column(name = "order_total")
    private Double orderTotal;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "order_priority")
    private String orderPriority;

    @Column(name = "discount")
    private Double discount;

    @Column(name = "shipping_fee")
    private Double shippingFee;

    @Column(name = "approval_status")
    private String approvalStatus;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Lob
    @Column(name = "plain_payload", columnDefinition = "CLOB")
    private String plainPayload;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // getters / setters (documented to satisfy repo style rules)

    /**
     * Primary key (database-generated).
     */
    public Long getId() { return id; }

    /**
     * Set primary key (used by JPA and tests).
     */
    public void setId(Long id) { this.id = id; }

    /**
     * Flowable case instance id this plain row belongs to.
     */
    public String getCaseInstanceId() { return caseInstanceId; }

    /**
     * Set the Flowable case instance id.
     */
    public void setCaseInstanceId(String caseInstanceId) { this.caseInstanceId = caseInstanceId; }

    /**
     * Normalized order total used for numeric filtering/aggregation.
     */
    public Double getOrderTotal() { return orderTotal; }

    /**
     * Update the order total.
     */
    public void setOrderTotal(Double orderTotal) { this.orderTotal = orderTotal; }

    /** Customer identifier (if available). */
    public String getCustomerId() { return customerId; }
    /** Set customer identifier. */
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    /** Customer display name. */
    public String getCustomerName() { return customerName; }
    /** Set customer display name. */
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    /** Order priority (mapped from metadata). */
    public String getOrderPriority() { return orderPriority; }
    /** Set order priority. */
    public void setOrderPriority(String orderPriority) { this.orderPriority = orderPriority; }

    /** Discount applied to the order (nullable). */
    public Double getDiscount() { return discount; }
    /** Set discount. */
    public void setDiscount(Double discount) { this.discount = discount; }

    /** Shipping fee applied to the order (nullable). */
    public Double getShippingFee() { return shippingFee; }
    /** Set shipping fee. */
    public void setShippingFee(Double shippingFee) { this.shippingFee = shippingFee; }

    /** Approval lifecycle status (PENDING/APPROVED/REJECTED). */
    public String getApprovalStatus() { return approvalStatus; }
    /** Set approval lifecycle status. */
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    /** Human-readable reason produced by the decision/approval step. */
    public String getDecisionReason() { return decisionReason; }
    /** Set decision reason. */
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    /**
     * JSON snapshot of the exported fields (CLOB). Kept for diagnostics and
     * to support field-check in the metadata UI.
     */
    public String getPlainPayload() { return plainPayload; }

    /**
     * Set the JSON payload. Expectation: small-to-medium size JSON; larger
     * payloads are stored as CLOB by the JPA provider.
     */
    public void setPlainPayload(String plainPayload) { this.plainPayload = plainPayload; }

    /** Entity creation timestamp (set on instantiation). */
    public OffsetDateTime getCreatedAt() { return createdAt; }
    /** Set creation timestamp (tests/migrations). */
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    /** Last updated timestamp; updated by application when mutating. */
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    /** Set last-updated timestamp. */
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
