package com.northstar.audit.web;

import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.AuditStep;
import com.northstar.audit.domain.SourceRecord;
import com.northstar.audit.service.DeletionIntentResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

public final class AuditDtos {
    private AuditDtos() {
    }

    public record StepRequest(
        @NotNull Integer stepNo,
        @NotBlank String phase,
        @NotBlank String systemId,
        @NotBlank String resourceId,
        @NotBlank String operation,
        @NotBlank String state,
        @NotNull String evidenceId
    ) {
        public AuditStep toEntity() {
            return new AuditStep(stepNo, phase, systemId, resourceId, operation, state, evidenceId);
        }
    }

    public record AuditRequest(@NotBlank String deletionStatus, @NotNull List<@Valid StepRequest> steps) {
    }

    public record StepResponse(int stepNo, String phase, String systemId, String resourceId,
                               String operation, String state, String evidenceId) {
        public static StepResponse from(AuditStep step) {
            return new StepResponse(step.getStepNo(), step.getPhase(), step.getSystemId(),
                step.getResourceId(), step.getOperation(), step.getState(), step.getEvidenceId());
        }
    }

    public record CaseSummary(String caseId, String email, LocalDateTime requestedAt,
                              String reportedStatus, String deletionStatus) {
        public static CaseSummary from(AuditCase auditCase) {
            return new CaseSummary(auditCase.getCaseId(), auditCase.getEmail(), auditCase.getRequestedAt(),
                auditCase.getReportedStatus(), auditCase.getDeletionStatus());
        }
    }

    public record CaseDetail(String caseId, String email, LocalDateTime requestedAt, String reportedStatus,
                             LocalDateTime reportedAt, LocalDateTime auditAt, String requestText,
                             String deletionStatus, String deletionIntent, String intentExplanation, List<StepResponse> steps) {
        public static CaseDetail from(AuditCase auditCase) {
            DeletionIntentResolver.Intent intent = DeletionIntentResolver.resolve(auditCase.getRequestText());
            return new CaseDetail(auditCase.getCaseId(), auditCase.getEmail(), auditCase.getRequestedAt(),
                auditCase.getReportedStatus(), auditCase.getReportedAt(), auditCase.getAuditAt(),
                auditCase.getRequestText(), auditCase.getDeletionStatus(), intent.status(), intent.explanation(),
                auditCase.getSteps().stream().map(StepResponse::from).toList());
        }
    }

    public record SourceRecordResponse(String sourceFile, int rowNumber, String evidenceId, String content) {
        public static SourceRecordResponse from(SourceRecord record) {
            return new SourceRecordResponse(record.getSourceFile(), record.getRowNumber(),
                record.getSourceFile() + "#" + record.getRowNumber(), record.getContent());
        }
    }
}
