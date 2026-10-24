package com.northstar.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.repository.AuditCaseRepository;
import com.northstar.audit.web.AuditDtos.AuditRequest;
import com.northstar.audit.web.AuditDtos.StepRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {
    @Mock
    private AuditCaseRepository cases;

    @Mock
    private EvidenceReferenceService evidenceReferences;

    private AuditService service;
    private AuditCase auditCase;

    @BeforeEach
    void setUp() {
        service = new AuditService(cases, evidenceReferences);
        auditCase = new AuditCase("case_001", "person@example.test", null, "COMPLETE", null, null, null);
        lenient().when(cases.findById("case_001")).thenReturn(Optional.of(auditCase));
        lenient().when(evidenceReferences.exists("evidence_01")).thenReturn(true);
        lenient().when(evidenceReferences.exists("evidence_02")).thenReturn(true);
    }

    @Test
    void savesNotApplicableWithNoSteps() {
        when(cases.findById("case_na")).thenReturn(Optional.of(new AuditCase(
            "case_na", "person@example.test", null, "NOT_STARTED", null, null,
            "Please stop these emails. I am not asking for deletion."
        )));
        when(cases.save(any(AuditCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.saveAudit("case_na", new AuditRequest("NOT_APPLICABLE", List.of()));

        assertEquals("NOT_APPLICABLE", result.deletionStatus());
        assertEquals(0, result.steps().size());
    }

    @Test
    void rejectsNotApplicableWhenRequestExplicitlyAsksForDeletion() {
        assertThrows(IllegalArgumentException.class,
            () -> service.saveAudit("case_001", new AuditRequest("NOT_APPLICABLE", List.of())));
    }

    @Test
    void rejectsDeletionWhenRequestExplicitlySaysNotToDelete() {
        when(cases.findById("case_no_delete")).thenReturn(Optional.of(new AuditCase(
            "case_no_delete", "person@example.test", null, "NOT_STARTED", null, null,
            "Please stop the promotional emails. I still need my account and am not asking for deletion."
        )));
        assertThrows(IllegalArgumentException.class,
            () -> service.saveAudit("case_no_delete", new AuditRequest("INCOMPLETE", List.of())));
    }

    @Test
    void rejectsIncompleteAuditWithoutEveryRequiredPhase() {
        AuditRequest request = new AuditRequest("INCOMPLETE", List.of(
            step(1, "FINDING", "REPORT_REMAINING_RECORD", "PRESENT"),
            step(2, "VERIFICATION", "VERIFY_RECORD_ABSENT", "ABSENT")
        ));

        assertThrows(IllegalArgumentException.class, () -> service.saveAudit("case_001", request));
    }

    @Test
    void rejectsCompleteAuditContainingFindings() {
        AuditRequest request = new AuditRequest("COMPLETE", List.of(
            step(1, "FINDING", "REPORT_REMAINING_RECORD", "PRESENT")
        ));

        assertThrows(IllegalArgumentException.class, () -> service.saveAudit("case_001", request));
    }

    @Test
    void requiresEvidenceForCompleteVerificationSteps() {
        AuditRequest request = new AuditRequest("COMPLETE", List.of(
            step(1, "VERIFICATION", "VERIFY_RECORD_ABSENT", "ABSENT", "")
        ));

        assertThrows(IllegalArgumentException.class, () -> service.saveAudit("case_001", request));
    }

    @Test
    void rejectsEvidenceReferencesNotPresentInSuppliedFiles() {
        AuditRequest request = new AuditRequest("COMPLETE", List.of(
            step(1, "VERIFICATION", "VERIFY_RECORD_ABSENT", "ABSENT", "evidence_999")
        ));

        assertThrows(IllegalArgumentException.class, () -> service.saveAudit("case_001", request));
    }

    @Test
    void requiresEmptyEvidenceForFutureIncompleteSteps() {
        AuditRequest request = new AuditRequest("INCOMPLETE", List.of(
            step(1, "FINDING", "REPORT_REMAINING_RECORD", "PRESENT", "evidence_01"),
            step(2, "REMEDIATION", "DELETE_RECORD", "ABSENT", "evidence_02"),
            step(3, "VERIFICATION", "VERIFY_RECORD_ABSENT", "ABSENT", "")
        ));

        assertThrows(IllegalArgumentException.class, () -> service.saveAudit("case_001", request));
    }

    @Test
    void rejectsInvalidOperationStatePair() {
        AuditRequest request = new AuditRequest("COMPLETE", List.of(
            step(1, "VERIFICATION", "VERIFY_RECORD_ABSENT", "PRESENT")
        ));

        assertThrows(IllegalArgumentException.class, () -> service.saveAudit("case_001", request));
    }

    @Test
    void sortsStepsByRequiredPhaseOperationAndResourceOrderAndAllowsEmptyEvidence() {
        when(cases.save(any(AuditCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditRequest request = new AuditRequest("INCOMPLETE", List.of(
            step(1, "VERIFICATION", "VERIFY_RECORD_ABSENT", "ABSENT", ""),
            step(2, "REMEDIATION", "DELETE_RECORD", "ABSENT", ""),
            step(3, "FINDING", "REPORT_ACTIVE_DATA_FLOW", "ACTIVE", "evidence_01"),
            step(4, "FINDING", "REPORT_REMAINING_RECORD", "PRESENT", "evidence_02"),
            step(5, "VERIFICATION", "VERIFY_DATA_FLOW_DISABLED", "DISABLED", "")
        ));

        var result = service.saveAudit("case_001", request);

        assertEquals(List.of(
            "REPORT_REMAINING_RECORD",
            "REPORT_ACTIVE_DATA_FLOW",
            "DELETE_RECORD",
            "VERIFY_RECORD_ABSENT",
            "VERIFY_DATA_FLOW_DISABLED"
        ), result.steps().stream().map(step -> step.operation()).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), result.steps().stream().map(step -> step.stepNo()).toList());
        assertEquals("", result.steps().get(2).evidenceId());
    }

    private static StepRequest step(int number, String phase, String operation, String state) {
        return new StepRequest(number, phase, "account_service", "account_001", operation, state, "evidence_01");
    }

    private static StepRequest step(int number, String phase, String operation, String state, String evidenceId) {
        return new StepRequest(number, phase, "account_service", "account_001", operation, state, evidenceId);
    }
}
