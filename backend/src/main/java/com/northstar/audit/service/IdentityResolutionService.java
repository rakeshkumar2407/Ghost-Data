package com.northstar.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.SourceRecord;
import com.northstar.audit.repository.AuditCaseRepository;
import com.northstar.audit.repository.SourceRecordRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityResolutionService {
    private final AuditCaseRepository cases;
    private final SourceRecordRepository sourceRecords;
    private final ObjectMapper objectMapper;

    public IdentityResolutionService(
        AuditCaseRepository cases,
        SourceRecordRepository sourceRecords,
        ObjectMapper objectMapper
    ) {
        this.cases = cases;
        this.sourceRecords = sourceRecords;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public IdentityResolution resolve(String caseId) {
        AuditCase auditCase = cases.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        List<SourceRecord> matchingHistory = sourceRecords.findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(
            "user_audit_log.csv", auditCase.getEmail());

        Set<String> userIds = new TreeSet<>();
        for (SourceRecord record : matchingHistory) {
            JsonNode row = parse(record.getContent());
            if (matchesIdentityValue(row, auditCase.getEmail())) {
                String userId = text(row, "user_id");
                if (!userId.isBlank()) userIds.add(userId);
            }
        }

        List<SourceRecord> matchingIdentities = sourceRecords.findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(
            "user_identities.csv", auditCase.getEmail());
        for (SourceRecord record : matchingIdentities) {
            JsonNode row = parse(record.getContent());
            if (auditCase.getEmail().equalsIgnoreCase(text(row, "email"))) {
                String userId = text(row, "user_id");
                if (!userId.isBlank()) userIds.add(userId);
            }
        }

        List<ResolvedIdentity> resolved = new ArrayList<>();
        for (String userId : userIds) {
            List<Identifier> identifiers = new ArrayList<>();
            List<SourceRecord> identityRows = sourceRecords.findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(
                "user_identities.csv", userId);
            for (SourceRecord record : identityRows) {
                JsonNode row = parse(record.getContent());
                if (!userId.equals(text(row, "user_id"))) continue;
                addIdentifier(identifiers, "name", text(row, "name"), "CURRENT_IDENTITY", text(row, "updated_at"));
                addIdentifier(identifiers, "email", text(row, "email"), "CURRENT_IDENTITY", text(row, "updated_at"));
                addIdentifier(identifiers, "phone", text(row, "phone"), "CURRENT_IDENTITY", text(row, "updated_at"));
                addIdentifier(identifiers, "email_hash", text(row, "email_hash"), "CURRENT_IDENTITY", text(row, "updated_at"));
            }
            List<SourceRecord> changes = sourceRecords.findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(
                "user_audit_log.csv", userId);
            for (SourceRecord record : changes) {
                JsonNode row = parse(record.getContent());
                if (!userId.equals(text(row, "user_id"))) continue;
                String field = text(row, "field_name");
                addIdentifier(identifiers, field, text(row, "old_value"), "HISTORICAL_IDENTITY", text(row, "event_time"));
                addIdentifier(identifiers, field, text(row, "new_value"), "HISTORICAL_IDENTITY", text(row, "event_time"));
            }
            identifiers.sort(Comparator.comparing(Identifier::field).thenComparing(Identifier::value)
                .thenComparing(Identifier::source));
            resolved.add(new ResolvedIdentity(userId, identifiers));
        }
        return new IdentityResolution(auditCase.getCaseId(), auditCase.getEmail(), resolved);
    }

    private JsonNode parse(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception exception) {
            throw new IllegalStateException("An imported identity source row contains invalid JSON.", exception);
        }
    }

    private static boolean matchesIdentityValue(JsonNode row, String email) {
        return email.equalsIgnoreCase(text(row, "old_value")) || email.equalsIgnoreCase(text(row, "new_value"));
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static void addIdentifier(List<Identifier> identifiers, String field, String value, String source, String eventTime) {
        if (field == null || field.isBlank() || value == null || value.isBlank()) return;
        boolean alreadyPresent = identifiers.stream().anyMatch(identifier ->
            identifier.field().equals(field) && identifier.value().equals(value) && identifier.source().equals(source));
        if (!alreadyPresent) identifiers.add(new Identifier(field, value, source, eventTime));
    }

    public record Identifier(String field, String value, String source, String eventTime) {
    }

    public record ResolvedIdentity(String userId, List<Identifier> identifiers) {
    }

    public record IdentityResolution(String caseId, String requestedEmail, List<ResolvedIdentity> identities) {
    }
}
