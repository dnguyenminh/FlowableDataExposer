package vn.com.fecredit.flowable.exposer.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "idx_order_report")
public class IdxReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_instance_id", nullable = false)
    private String caseInstanceId;

    @Column(name = "total_amount")
    private Double totalAmount;

    @Column(name = "item_1_id")
    private String item1Id;

    @Column(name = "color_attr")
    private String colorAttr;

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCaseInstanceId() { return caseInstanceId; }
    public void setCaseInstanceId(String caseInstanceId) { this.caseInstanceId = caseInstanceId; }
    public Double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }
    public String getItem1Id() { return item1Id; }
    public void setItem1Id(String item1Id) { this.item1Id = item1Id; }
    public String getColorAttr() { return colorAttr; }
    public void setColorAttr(String colorAttr) { this.colorAttr = colorAttr; }
}
