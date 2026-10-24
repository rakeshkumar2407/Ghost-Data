package com.northstar.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_step")
public class AuditStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false)
    private AuditCase auditCase;

    @Column(name = "step_no", nullable = false)
    private int stepNo;

    @Column(nullable = false, length = 20)
    private String phase;

    @Column(name = "system_id", nullable = false, length = 120)
    private String systemId;

    @Column(name = "resource_id", nullable = false, length = 240)
    private String resourceId;

    @Column(nullable = false, length = 40)
    private String operation;

    @Column(nullable = false, length = 24)
    private String state;

    @Column(name = "evidence_id", nullable = false, length = 160)
    private String evidenceId;

    protected AuditStep() {
    }

    public AuditStep(int stepNo, String phase, String systemId, String resourceId,
                     String operation, String state, String evidenceId) {
        this.stepNo = stepNo;
        this.phase = phase;
        this.systemId = systemId;
        this.resourceId = resourceId;
        this.operation = operation;
        this.state = state;
        this.evidenceId = evidenceId;
    }

    public void setAuditCase(AuditCase auditCase) { this.auditCase = auditCase; }
    public int getStepNo() { return stepNo; }
    public String getPhase() { return phase; }
    public String getSystemId() { return systemId; }
    public String getResourceId() { return resourceId; }
    public String getOperation() { return operation; }
    public String getState() { return state; }
    public String getEvidenceId() { return evidenceId; }
}
