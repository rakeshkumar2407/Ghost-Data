package com.northstar.audit.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.domain.AuditCase;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CsvExportService {
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CsvExportService(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public String export() {
        List<AuditCase> cases = auditService.allForExport();
        List<String> pending = cases.stream().filter(item -> item.getDeletionStatus() == null)
            .map(AuditCase::getCaseId).toList();
        if (!pending.isEmpty()) {
            throw new IllegalStateException("Audit every case before export. Pending cases: "
                + String.join(", ", pending.subList(0, Math.min(10, pending.size())))
                + (pending.size() > 10 ? " (and " + (pending.size() - 10) + " more)" : ""));
        }
        StringWriter output = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(output, CSVFormat.DEFAULT.builder()
            .setHeader("case_id", "deletion_status", "steps_json").build())) {
            for (AuditCase auditCase : cases) {
                List<ExportStep> steps = auditCase.getSteps().stream().map(step ->
                    new ExportStep(step.getStepNo(), step.getPhase(), step.getSystemId(), step.getResourceId(),
                        step.getOperation(), step.getState(), step.getEvidenceId())).toList();
                printer.printRecord(auditCase.getCaseId(), auditCase.getDeletionStatus(), objectMapper.writeValueAsString(steps));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate the output CSV.", exception);
        }
        return output.toString();
    }

    private record ExportStep(
        @JsonProperty("step_no") int stepNo,
        String phase,
        @JsonProperty("system_id") String systemId,
        @JsonProperty("resource_id") String resourceId,
        String operation,
        String state,
        @JsonProperty("evidence_id") String evidenceId
    ) {
    }
}
