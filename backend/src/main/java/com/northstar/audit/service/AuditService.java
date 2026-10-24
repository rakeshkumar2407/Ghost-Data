package com.northstar.audit.service;

import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.AuditStep;
import com.northstar.audit.repository.AuditCaseRepository;
import com.northstar.audit.web.AuditDtos.AuditRequest;
import com.northstar.audit.web.AuditDtos.CaseDetail;
import com.northstar.audit.web.AuditDtos.CaseSummary;
import com.northstar.audit.web.AuditDtos.StepRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private static final Map<String, Map<String, String>> ALLOWED = Map.of(
        "FINDING", Map.of(
            "REPORT_REMAINING_RECORD", "PRESENT",
            "REPORT_EXPOSED_PII", "PRESENT",
            "REPORT_ACTIVE_DATA_FLOW", "ACTIVE",
            "REPORT_RESTORE_RISK", "ACTIVE"
        ),
        "REMEDIATION", Map.of(
            "DISABLE_DATA_FLOW", "DISABLED",
            "BLOCK_RESTORE", "BLOCKED",
            "DELETE_RECORD", "ABSENT",
            "REDACT_RECORD", "REDACTED",
            "ANONYMIZE_RECORD", "ANONYMIZED"
        ),
        "VERIFICATION", Map.of(
            "VERIFY_RECORD_ABSENT", "ABSENT",
            "VERIFY_RECORD_PRESENT", "PRESENT",
            "VERIFY_RECORD_REDACTED", "REDACTED",
            "VERIFY_RECORD_ANONYMIZED", "ANONYMIZED",
            "VERIFY_DATA_FLOW_DISABLED", "DISABLED",
            "VERIFY_RESTORE_BLOCKED", "BLOCKED"
        )
    );
    private static final Set<String> STATUSES = Set.of("COMPLETE", "INCOMPLETE", "NOT_APPLICABLE");
    private static final Set<String> FILTER_STATUSES = Set.of("COMPLETE", "INCOMPLETE", "NOT_APPLICABLE", "PENDING");
    private static final Map<String, Integer> OPERATION_ORDER = Map.ofEntries(
        Map.entry("REPORT_REMAINING_RECORD", 0),
        Map.entry("REPORT_EXPOSED_PII", 1),
        Map.entry("REPORT_ACTIVE_DATA_FLOW", 2),
        Map.entry("REPORT_RESTORE_RISK", 3),
        Map.entry("DISABLE_DATA_FLOW", 0),
        Map.entry("BLOCK_RESTORE", 1),
        Map.entry("DELETE_RECORD", 2),
        Map.entry("REDACT_RECORD", 3),
        Map.entry("ANONYMIZE_RECORD", 4),
        Map.entry("VERIFY_RECORD_ABSENT", 0),
        Map.entry("VERIFY_RECORD_PRESENT", 1),
        Map.entry("VERIFY_RECORD_REDACTED", 2),
        Map.entry("VERIFY_RECORD_ANONYMIZED", 3),
        Map.entry("VERIFY_DATA_FLOW_DISABLED", 4),
        Map.entry("VERIFY_RESTORE_BLOCKED", 5)
    );

    private final AuditCaseRepository cases;
    private final EvidenceReferenceService evidenceReferences;

    public AuditService(AuditCaseRepository cases, EvidenceReferenceService evidenceReferences) {
        this.cases = cases;
        this.evidenceReferences = evidenceReferences;
    }

    @Transactional(readOnly = true)
    public Page<CaseSummary> list(String search, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100.");
        }
        String normalizedStatus = status == null || status.isBlank() || "ALL".equals(status) ? null : status;
        if (normalizedStatus != null && !FILTER_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("Unsupported deletion status.");
        }
        return cases.findCases(blankToNull(search), normalizedStatus,
            PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "caseId"))).map(CaseSummary::from);
    }

    @Transactional(readOnly = true)
    public CaseDetail get(String caseId) {
        return CaseDetail.from(cases.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId)));
    }

    @Transactional
    public CaseDetail saveAudit(String caseId, AuditRequest request) {
        AuditCase auditCase = cases.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        validate(auditCase, request);
        List<AuditStep> orderedSteps = request.steps().stream()
            .sorted(Comparator
                .comparingInt((StepRequest step) -> phaseOrder(step.phase()))
                .thenComparingInt(step -> OPERATION_ORDER.get(step.operation()))
                .thenComparing(StepRequest::systemId)
                .thenComparing(StepRequest::resourceId))
            .map(StepRequest::toEntity)
            .toList();
        List<AuditStep> steps = java.util.stream.IntStream.range(0, orderedSteps.size())
            .mapToObj(index -> {
                AuditStep step = orderedSteps.get(index);
                return new AuditStep(index + 1, step.getPhase(), step.getSystemId(), step.getResourceId(),
                    step.getOperation(), step.getState(), step.getEvidenceId());
            })
            .toList();
        auditCase.saveAudit(request.deletionStatus(), steps);
        return CaseDetail.from(cases.save(auditCase));
    }

    @Transactional(readOnly = true)
    public void validateDraft(String caseId, AuditRequest request) {
        AuditCase auditCase = cases.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        validate(auditCase, request);
    }

    public Map<String, Object> dashboard(long sourceFiles) {
        long total = cases.count();
        long pending = cases.countByDeletionStatusIsNull();
        return Map.of(
            "totalCases", total,
            "pendingCases", pending,
            "completeCases", cases.countByDeletionStatus("COMPLETE"),
            "incompleteCases", cases.countByDeletionStatus("INCOMPLETE"),
            "notApplicableCases", cases.countByDeletionStatus("NOT_APPLICABLE"),
            "sourceFiles", sourceFiles,
            "reviewedPercent", total == 0 ? 0 : Math.round((total - pending) * 100.0 / total)
        );
    }

    @Transactional(readOnly = true)
    public List<AuditCase> allForExport() {
        return cases.findAll(Sort.by(Sort.Direction.ASC, "caseId"));
    }

    private void validate(AuditCase auditCase, AuditRequest request) {
        String status = request.deletionStatus();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Deletion status must be COMPLETE, INCOMPLETE, or NOT_APPLICABLE.");
        }
        var intent = DeletionIntentResolver.resolve(auditCase.getRequestText());
        if ("NOT_REQUESTED".equals(intent.status()) && !"NOT_APPLICABLE".equals(status)) {
            throw new IllegalArgumentException("The request explicitly does not authorize deletion; use NOT_APPLICABLE.");
        }
        if ("NOT_APPLICABLE".equals(status) && !"NOT_REQUESTED".equals(intent.status())) {
            throw new IllegalArgumentException("NOT_APPLICABLE requires explicit evidence that deletion was not requested.");
        }
        List<StepRequest> steps = request.steps();
        if ("NOT_APPLICABLE".equals(status)) {
            if (!steps.isEmpty()) {
                throw new IllegalArgumentException("NOT_APPLICABLE cases must have an empty steps array.");
            }
            return;
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Reviewed cases need evidence steps.");
        }

        boolean sawFinding = false;
        boolean sawRemediation = false;
        boolean sawVerification = false;
        for (int index = 0; index < steps.size(); index++) {
            StepRequest step = steps.get(index);
            if (step.stepNo() != index + 1) {
                throw new IllegalArgumentException("Step numbers must start at 1 and increase consecutively.");
            }
            Map<String, String> operations = ALLOWED.get(step.phase());
            if (operations == null || !step.state().equals(operations.get(step.operation()))) {
                throw new IllegalArgumentException("Operation, phase, and state must match the allowed operation list.");
            }
            sawFinding |= "FINDING".equals(step.phase());
            sawRemediation |= "REMEDIATION".equals(step.phase());
            sawVerification |= "VERIFICATION".equals(step.phase());
            boolean requiresCitation = "COMPLETE".equals(status)
                || "FINDING".equals(step.phase());
            boolean futureStep = "INCOMPLETE".equals(status)
                && ("REMEDIATION".equals(step.phase()) || "VERIFICATION".equals(step.phase()));
            if (requiresCitation && (step.evidenceId() == null || step.evidenceId().isBlank())) {
                throw new IllegalArgumentException("Findings and COMPLETE verification steps must cite supplied evidence.");
            }
            if (requiresCitation && !evidenceReferences.exists(step.evidenceId())) {
                throw new IllegalArgumentException("Evidence ID does not exist in the supplied manifest or source records.");
            }
            if (futureStep && !"".equals(step.evidenceId())) {
                throw new IllegalArgumentException("INCOMPLETE remediation and future verification steps must have an empty evidence ID.");
            }
            if ("COMPLETE".equals(status) && !"VERIFICATION".equals(step.phase())) {
                throw new IllegalArgumentException("COMPLETE cases may contain verification steps only.");
            }
        }
        if ("INCOMPLETE".equals(status) && !(sawFinding && sawRemediation && sawVerification)) {
            throw new IllegalArgumentException("INCOMPLETE cases require findings, remediation, and verification phases.");
        }
    }

    private static int phaseOrder(String phase) {
        return switch (phase) {
            case "FINDING" -> 0;
            case "REMEDIATION" -> 1;
            case "VERIFICATION" -> 2;
            default -> throw new IllegalArgumentException("Unsupported phase.");
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
