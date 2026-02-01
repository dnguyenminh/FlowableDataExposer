package vn.com.fecredit.flowable.exposer.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

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

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCaseInstanceId() { return caseInstanceId; }
    public void setCaseInstanceId(String caseInstanceId) { this.caseInstanceId = caseInstanceId; }
    public Double getOrderTotal() { return orderTotal; }
    public void setOrderTotal(Double orderTotal) { this.orderTotal = orderTotal; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getOrderPriority() { return orderPriority; }
    public void setOrderPriority(String orderPriority) { this.orderPriority = orderPriority; }
    public Double getDiscount() { return discount; }
    public void setDiscount(Double discount) { this.discount = discount; }
    public Double getShippingFee() { return shippingFee; }
    public void setShippingFee(Double shippingFee) { this.shippingFee = shippingFee; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public String getPlainPayload() { return plainPayload; }
    public void setPlainPayload(String plainPayload) { this.plainPayload = plainPayload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
