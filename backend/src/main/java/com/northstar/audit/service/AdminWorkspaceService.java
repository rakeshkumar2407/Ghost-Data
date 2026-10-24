package com.northstar.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.SourceRecord;
import com.northstar.audit.repository.AuditCaseRepository;
import com.northstar.audit.repository.SourceRecordRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminWorkspaceService {
    private static final Set<String> SUPPORTED_TABLES = Set.of(
        "deletion_requests.csv",
        "deletion_rules.csv",
        "evidence_files.csv",
        "system_activity.csv",
        "user_audit_log.csv",
        "user_identities.csv"
    );

    private final AuditCaseRepository cases;
    private final SourceRecordRepository sourceRecords;
    private final ObjectMapper objectMapper;

    public AdminWorkspaceService(
        AuditCaseRepository cases,
        SourceRecordRepository sourceRecords,
        ObjectMapper objectMapper
    ) {
        this.cases = cases;
        this.sourceRecords = sourceRecords;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Map<String, Long> tableCounts = tableCounts();
        long totalRows = tableCounts.values().stream().mapToLong(Long::longValue).sum();
        return new Overview(
            cases.count(),
            cases.countByDeletionStatusIsNull(),
            cases.countByDeletionStatus("COMPLETE"),
            cases.countByDeletionStatus("INCOMPLETE"),
            cases.countByDeletionStatus("NOT_APPLICABLE"),
            tableCounts.getOrDefault("user_identities.csv", 0L),
            tableCounts.getOrDefault("user_audit_log.csv", 0L),
            tableCounts.getOrDefault("system_activity.csv", 0L),
            totalRows,
            tableCounts.size()
        );
    }

    @Transactional(readOnly = true)
    public List<TableSummary> tables() {
        return tableCounts().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new TableSummary(entry.getKey(), entry.getValue(), category(entry.getKey())))
            .toList();
    }

    @Transactional(readOnly = true)
    public Page<SourceRow> tableRows(String sourceFile, String search, int page, int size) {
        validatePaging(page, size);
        if (!SUPPORTED_TABLES.contains(sourceFile) || sourceRecords.countBySourceFile(sourceFile) == 0) {
            if (!"deletion_requests.csv".equals(sourceFile) || cases.count() == 0) {
                throw new IllegalArgumentException("Unknown or unavailable imported data table: " + sourceFile);
            }
        }
        String query = search == null || search.isBlank() ? null : search.trim();
        if (query != null && query.length() < 2) {
            throw new IllegalArgumentException("Enter at least 2 characters to search a data table.");
        }
        if ("deletion_requests.csv".equals(sourceFile)) {
            Page<AuditCase> requests = cases.findCases(query, null,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "caseId")));
            AtomicInteger rowNumber = new AtomicInteger(page * size + 1);
            return requests.map(auditCase ->
                new SourceRow(rowNumber.getAndIncrement(), objectMapper.valueToTree(requestRow(auditCase))));
        }
        PageRequest pageableByRow = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "rowNumber"));
        Page<SourceRecord> rows = query == null
            ? sourceRecords.findBySourceFileOrderByRowNumberAsc(sourceFile, pageableByRow)
            : sourceRecords.findBySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(sourceFile, query, pageableByRow);
        return rows.map(this::sourceRow);
    }

    @Transactional(readOnly = true)
    public CustomerDetail customer(String userId) {
        String normalizedUserId = userId == null ? "" : userId.trim();
        List<SourceRecord> currentMatches = sourceRecords
            .findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc("user_identities.csv", normalizedUserId);
        JsonNode identity = currentMatches.stream()
            .map(this::parse)
            .filter(row -> normalizedUserId.equals(text(row, "user_id")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + normalizedUserId));

        List<JsonNode> history = sourceRecords
            .findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc("user_audit_log.csv", normalizedUserId)
            .stream().map(this::parse)
            .filter(row -> normalizedUserId.equals(text(row, "user_id")))
            .sorted(Comparator.comparing(row -> text(row, "event_time")))
            .toList();

        Set<String> emails = new java.util.HashSet<>();
        emails.add(text(identity, "email").toLowerCase(Locale.ROOT));
        history.stream()
            .filter(row -> "email".equalsIgnoreCase(text(row, "field_name")))
            .forEach(row -> {
                addEmail(emails, text(row, "old_value"));
                addEmail(emails, text(row, "new_value"));
            });

        List<Map<String, Object>> deletionRequests = cases.findAll().stream()
            .filter(auditCase -> emails.contains(auditCase.getEmail().toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparing(AuditCase::getCaseId))
            .map(this::requestRow)
            .toList();

        Pattern userIdToken = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(normalizedUserId) + "(?![A-Za-z0-9_])");
        List<JsonNode> activity = sourceRecords.findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(
                "system_activity.csv", normalizedUserId)
            .stream().map(this::parse)
            .filter(row -> userIdToken.matcher(text(row, "details")).find())
            .sorted(Comparator.comparing(row -> text(row, "event_time")))
            .toList();

        return new CustomerDetail(identity, history, deletionRequests, activity);
    }

    private Map<String, Long> tableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : sourceRecords.countRowsBySourceFile()) {
            String sourceFile = (String) row[0];
            if (SUPPORTED_TABLES.contains(sourceFile)) {
                counts.put(sourceFile, ((Number) row[1]).longValue());
            }
        }
        counts.put("deletion_requests.csv", cases.count());
        return counts;
    }

    private SourceRow sourceRow(SourceRecord record) {
        return new SourceRow(record.getRowNumber(), parse(record.getContent()));
    }

    private JsonNode parse(SourceRecord record) {
        return parse(record.getContent());
    }

    private JsonNode parse(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("An imported data row contains invalid JSON.", exception);
        }
    }

    private Map<String, Object> requestRow(AuditCase auditCase) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case_id", auditCase.getCaseId());
        row.put("email", auditCase.getEmail());
        row.put("requested_at", auditCase.getRequestedAt());
        row.put("reported_status", auditCase.getReportedStatus());
        row.put("reported_at", auditCase.getReportedAt());
        row.put("audit_at", auditCase.getAuditAt());
        row.put("request_text", auditCase.getRequestText());
        row.put("deletion_status", auditCase.getDeletionStatus());
        row.put("steps", auditCase.getSteps().stream().map(step -> Map.of(
            "step_no", step.getStepNo(),
            "phase", step.getPhase(),
            "system_id", step.getSystemId(),
            "resource_id", step.getResourceId(),
            "operation", step.getOperation(),
            "state", step.getState(),
            "evidence_id", step.getEvidenceId()
        )).toList());
        return row;
    }

    private static void validatePaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100.");
        }
    }

    private static void addEmail(Set<String> emails, String value) {
        if (value != null && value.contains("@")) emails.add(value.toLowerCase(Locale.ROOT));
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String category(String file) {
        return switch (file) {
            case "user_identities.csv" -> "Customer profiles";
            case "user_audit_log.csv" -> "Identity history";
            case "system_activity.csv" -> "System activity";
            case "deletion_requests.csv" -> "Privacy requests";
            case "deletion_rules.csv" -> "Policy rules";
            case "evidence_files.csv" -> "Evidence manifest";
            default -> "Imported data";
        };
    }

    public record Overview(
        long deletionRequests,
        long pendingAudits,
        long completedAudits,
        long incompleteAudits,
        long notApplicableRequests,
        long customerProfiles,
        long identityChanges,
        long systemActivities,
        long databaseRows,
        long dataTables
    ) {
    }

    public record TableSummary(String sourceFile, long rowCount, String category) {
    }

    public record SourceRow(int rowNumber, JsonNode data) {
    }

    public record CustomerDetail(
        JsonNode profile,
        List<JsonNode> identityHistory,
        List<Map<String, Object>> deletionRequests,
        List<JsonNode> systemActivity
    ) {
    }
}
