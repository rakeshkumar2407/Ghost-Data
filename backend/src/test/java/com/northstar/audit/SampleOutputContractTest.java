package com.northstar.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

class SampleOutputContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Map<String, String>> OPERATIONS = Map.of(
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
    private static final Map<String, Integer> PHASE_ORDER = Map.of("FINDING", 0, "REMEDIATION", 1, "VERIFICATION", 2);
    private static final Map<String, Integer> OPERATION_ORDER = Map.ofEntries(
        Map.entry("REPORT_REMAINING_RECORD", 0), Map.entry("REPORT_EXPOSED_PII", 1),
        Map.entry("REPORT_ACTIVE_DATA_FLOW", 2), Map.entry("REPORT_RESTORE_RISK", 3),
        Map.entry("DISABLE_DATA_FLOW", 0), Map.entry("BLOCK_RESTORE", 1), Map.entry("DELETE_RECORD", 2),
        Map.entry("REDACT_RECORD", 3), Map.entry("ANONYMIZE_RECORD", 4),
        Map.entry("VERIFY_RECORD_ABSENT", 0), Map.entry("VERIFY_RECORD_PRESENT", 1),
        Map.entry("VERIFY_RECORD_REDACTED", 2), Map.entry("VERIFY_RECORD_ANONYMIZED", 3),
        Map.entry("VERIFY_DATA_FLOW_DISABLED", 4), Map.entry("VERIFY_RESTORE_BLOCKED", 5)
    );

    @Test
    void sampleAnswersMatchTheRequiredRowsStatusesAndOrderedStepContract() throws Exception {
        Path file = TestDatasetPath.resolve().resolve("sample_output.csv");
        try (Reader reader = Files.newBufferedReader(file);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            assertEquals(32, parser.getRecords().size());
            for (var row : parser.getRecords()) {
                assertTrue(row.isMapped("case_id"));
                assertTrue(row.get("deletion_status").equals("COMPLETE")
                    || row.get("deletion_status").equals("INCOMPLETE")
                    || row.get("deletion_status").equals("NOT_APPLICABLE"));
                JsonNode steps = JSON.readTree(row.get("steps_json"));
                assertTrue(steps.isArray());
                if (row.get("deletion_status").equals("NOT_APPLICABLE")) {
                    assertEquals(0, steps.size());
                }
                if (row.get("deletion_status").equals("COMPLETE")) {
                    for (JsonNode step : steps) assertEquals("VERIFICATION", step.get("phase").asText());
                }
                String previousKey = "";
                for (int index = 0; index < steps.size(); index++) {
                    JsonNode step = steps.get(index);
                    assertEquals(index + 1, step.get("step_no").asInt());
                    String phase = step.get("phase").asText();
                    String operation = step.get("operation").asText();
                    assertEquals(OPERATIONS.get(phase).get(operation), step.get("state").asText());
                    assertTrue(step.has("system_id") && step.has("resource_id") && step.has("evidence_id"));
                    String evidenceId = step.get("evidence_id").asText();
                    if (row.get("deletion_status").equals("COMPLETE") || phase.equals("FINDING")) {
                        assertTrue(!evidenceId.isBlank(), row.get("case_id") + " needs a cited evidence ID.");
                    }
                    if (row.get("deletion_status").equals("INCOMPLETE")
                        && (phase.equals("REMEDIATION") || phase.equals("VERIFICATION"))) {
                        assertEquals("", evidenceId, row.get("case_id") + " future steps must not cite evidence.");
                    }
                    String key = "%d|%d|%s|%s".formatted(PHASE_ORDER.get(phase), OPERATION_ORDER.get(operation),
                        step.get("system_id").asText(), step.get("resource_id").asText());
                    assertTrue(previousKey.compareTo(key) <= 0, row.get("case_id") + " has unordered steps.");
                    previousKey = key;
                }
            }
        }
    }
}
