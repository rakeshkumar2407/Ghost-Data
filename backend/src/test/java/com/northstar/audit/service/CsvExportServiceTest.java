package com.northstar.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.AuditStep;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

class CsvExportServiceTest {
    private final AuditService auditService = mock(AuditService.class);
    private final CsvExportService exportService = new CsvExportService(auditService, new ObjectMapper());

    @Test
    void exportsStatusAndStepsAsCsvWithJsonSteps() throws Exception {
        AuditCase auditCase = new AuditCase("case_001", "person@example.test", null, "COMPLETE", null, null, null);
        auditCase.saveAudit("COMPLETE", List.of(
            new AuditStep(1, "VERIFICATION", "account_service", "account_001",
                "VERIFY_RECORD_ABSENT", "ABSENT", "evidence_01")
        ));
        when(auditService.allForExport()).thenReturn(List.of(auditCase));

        try (CSVParser parser = CSVParser.parse(exportService.export(), CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true).build())) {
            var rows = parser.getRecords();
            assertEquals(1, rows.size());
            assertEquals("case_001", rows.getFirst().get("case_id"));
            assertEquals("COMPLETE", rows.getFirst().get("deletion_status"));
            var step = new ObjectMapper().readTree(rows.getFirst().get("steps_json")).get(0);
            assertEquals(1, step.get("step_no").asInt());
            assertEquals("VERIFICATION", step.get("phase").asText());
            assertEquals("account_service", step.get("system_id").asText());
            assertEquals("evidence_01", step.get("evidence_id").asText());
        }
    }

    @Test
    void blocksExportUntilEveryCaseHasAnAuditDecision() {
        when(auditService.allForExport()).thenReturn(List.of(
            new AuditCase("case_001", "person@example.test", null, "COMPLETE", null, null, null)
        ));

        assertThrows(IllegalStateException.class, exportService::export);
    }
}
