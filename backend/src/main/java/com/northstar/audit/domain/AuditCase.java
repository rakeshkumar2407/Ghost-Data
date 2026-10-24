package com.northstar.audit.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.OrderBy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "audit_case")
public class AuditCase {
    @Id
    @Column(name = "case_id", length = 120)
    private String caseId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "reported_status", length = 40)
    private String reportedStatus;

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    @Column(name = "audit_at")
    private LocalDateTime auditAt;

    @Column(name = "request_text", columnDefinition = "text")
    private String requestText;

    @Column(name = "deletion_status", length = 24)
    private String deletionStatus;

    @OneToMany(mappedBy = "auditCase", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepNo ASC")
    private List<AuditStep> steps = new ArrayList<>();

    protected AuditCase() {
    }

    public AuditCase(String caseId, String email, LocalDateTime requestedAt, String reportedStatus,
                     LocalDateTime reportedAt, LocalDateTime auditAt, String requestText) {
        this.caseId = caseId;
        this.email = email;
        this.requestedAt = requestedAt;
        this.reportedStatus = reportedStatus;
        this.reportedAt = reportedAt;
        this.auditAt = auditAt;
        this.requestText = requestText;
    }

    public void saveAudit(String status, List<AuditStep> newSteps) {
        steps.clear();
        newSteps.forEach(step -> {
            step.setAuditCase(this);
            steps.add(step);
        });
        deletionStatus = status;
    }

    public void updateRequest(LocalDateTime requestedAt, String reportedStatus, LocalDateTime reportedAt,
                              LocalDateTime auditAt, String requestText) {
        this.requestedAt = requestedAt;
        this.reportedStatus = reportedStatus;
        this.reportedAt = reportedAt;
        this.auditAt = auditAt;
        this.requestText = requestText;
    }

    public String getCaseId() { return caseId; }
    public String getEmail() { return email; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public String getReportedStatus() { return reportedStatus; }
    public LocalDateTime getReportedAt() { return reportedAt; }
    public LocalDateTime getAuditAt() { return auditAt; }
    public String getRequestText() { return requestText; }
    public String getDeletionStatus() { return deletionStatus; }
    public List<AuditStep> getSteps() { return steps; }
}
