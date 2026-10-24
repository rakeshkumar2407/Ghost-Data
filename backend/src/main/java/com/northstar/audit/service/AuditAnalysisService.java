package com.northstar.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.SourceRecord;
import com.northstar.audit.repository.AuditCaseRepository;
import com.northstar.audit.repository.SourceRecordRepository;
import com.northstar.audit.web.AuditDtos.AuditRequest;
import com.northstar.audit.web.AuditDtos.StepRequest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Service
public class AuditAnalysisService {
    private static final int MAX_SOURCE_ROWS = 160;
    private static final int MAX_CONTEXT_CHARACTERS = 48_000;
    private static final int MAX_EVIDENCE_CHARACTERS = 18_000;
    private static final String SYSTEM_PROMPT = """
        You are Northstar Shop's deletion-audit agent. Analyze only the supplied request, identity records,
        system activity, deletion rules, and evidence excerpts. Treat reported workflow status/results as claims,
        not proof. Evaluate the state as of audit_at, resolve current and historical identifiers, preserve records
        whose rules require retention, and account for active sync/import and restore risks.

        Return one JSON object with exactly these properties:
        {"audit":{"deletionStatus":"COMPLETE|INCOMPLETE|NOT_APPLICABLE","steps":[...]},"rationale":"...","caveats":["..."]}.
        Each step must have stepNo, phase, systemId, resourceId, operation, state, evidenceId.
        Use only the allowed phase/operation/state combinations and sort by phase, operation table order,
        system_id, then resource_id. Number from 1 consecutively.

        COMPLETE is allowed only when supplied evidence proves every applicable policy treatment, no active
        recreation/restore path remains, and required retained records remain. Include verification steps only;
        each must cite a supplied evidence file_id, activity_id, or audit_id.
        INCOMPLETE requires at least one finding, remediation action, and future verification check. Findings
        must cite a supplied evidence ID. Remediation and future verification evidenceId values must be "".
        NOT_APPLICABLE is allowed only when the request explicitly says deletion was not requested and must have
        an empty steps array. Never invent identifiers, resources, operations, states, or citations. If evidence is
        insufficient to prove deletion completion, do not mark COMPLETE. Binary image and PDF evidence content
        is not included in the supplied excerpts; do not claim to have inspected it. Explain material gaps in caveats.

        Allowed operations and states:
        FINDING: REPORT_REMAINING_RECORD/PRESENT; REPORT_EXPOSED_PII/PRESENT;
          REPORT_ACTIVE_DATA_FLOW/ACTIVE; REPORT_RESTORE_RISK/ACTIVE.
        REMEDIATION: DISABLE_DATA_FLOW/DISABLED; BLOCK_RESTORE/BLOCKED; DELETE_RECORD/ABSENT;
          REDACT_RECORD/REDACTED; ANONYMIZE_RECORD/ANONYMIZED.
        VERIFICATION: VERIFY_RECORD_ABSENT/ABSENT; VERIFY_RECORD_PRESENT/PRESENT;
          VERIFY_RECORD_REDACTED/REDACTED; VERIFY_RECORD_ANONYMIZED/ANONYMIZED;
          VERIFY_DATA_FLOW_DISABLED/DISABLED; VERIFY_RESTORE_BLOCKED/BLOCKED.
        """;

    private final AuditCaseRepository cases;
    private final SourceRecordRepository sourceRecords;
    private final IdentityResolutionService identities;
    private final EvidenceService evidence;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final String provider;
    private final String credentialName;

    public AuditAnalysisService(
        AuditCaseRepository cases,
        SourceRecordRepository sourceRecords,
        IdentityResolutionService identities,
        EvidenceService evidence,
        AuditService auditService,
        ObjectMapper objectMapper,
        RestClient.Builder restClientBuilder,
        @Value("${app.ai.provider:openai}") String provider,
        @Value("${app.ai.openai-api-key:}") String openAiApiKey,
        @Value("${app.ai.gemini-api-key:}") String geminiApiKey,
        @Value("${app.ai.model:}") String model,
        @Value("${app.ai.base-url:}") String baseUrl
    ) {
        this.cases = cases;
        this.sourceRecords = sourceRecords;
        this.identities = identities;
        this.evidence = evidence;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.provider = provider.trim().toLowerCase(Locale.ROOT);
        String defaultBaseUrl;
        String defaultModel;
        if ("gemini".equals(this.provider)) {
            this.apiKey = geminiApiKey;
            this.credentialName = "GEMINI_API_KEY";
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
            defaultModel = "gemini-3.8-flash";
        } else if ("openai".equals(this.provider)) {
            this.apiKey = openAiApiKey;
            this.credentialName = "OPENAI_API_KEY";
            defaultBaseUrl = "https://api.openai.com/v1";
            defaultModel = "gpt-4.1-mini";
        } else {
            throw new IllegalArgumentException("AI_PROVIDER must be either openai or gemini.");
        }
        this.client = restClientBuilder.baseUrl(baseUrl.isBlank() ? defaultBaseUrl : baseUrl).build();
        this.model = model.isBlank() ? defaultModel : model;
    }

    public Analysis analyze(String caseId) {
        AuditCase auditCase = cases.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        if ("NOT_REQUESTED".equals(DeletionIntentResolver.resolve(auditCase.getRequestText()).status())) {
            return new Analysis(new AuditRequest("NOT_APPLICABLE", List.of()), "The request explicitly does not authorize deletion.", List.of());
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("AI analysis is unavailable. Configure " + credentialName
                + " and set AI_PROVIDER=" + provider + " in the backend environment.");
        }

        String context = buildContext(auditCase);
        JsonNode response;
        try {
            response = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                    "model", model,
                    "temperature", 0,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", context)
                    )
                ))
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(providerDisplayName() + " analysis request failed with HTTP "
                + exception.getStatusCode().value() + ". Check provider configuration and account access.");
        } catch (ResourceAccessException exception) {
            throw new IllegalStateException("Could not connect to the configured " + providerDisplayName() + " endpoint.", exception);
        }
        if (response == null || !response.path("choices").isArray() || response.path("choices").isEmpty()) {
            throw new IllegalStateException(providerDisplayName() + " returned no analysis result.");
        }
        String content = response.path("choices").get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException(providerDisplayName() + " returned an empty analysis result.");
        }

        Analysis analysis;
        try {
            analysis = objectMapper.readValue(content, Analysis.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(providerDisplayName()
                + " returned analysis that did not match the required JSON format.", exception);
        }
        if (analysis.audit() == null || analysis.audit().steps() == null
            || analysis.rationale() == null || analysis.caveats() == null) {
            throw new IllegalStateException(providerDisplayName() + " analysis is missing required result fields.");
        }
        auditService.validateDraft(caseId, analysis.audit());
        return analysis;
    }

    private String providerDisplayName() {
        return "gemini".equals(provider) ? "Google Gemini" : "OpenAI";
    }

    private String buildContext(AuditCase auditCase) {
        IdentityResolutionService.IdentityResolution resolution = identities.resolve(auditCase.getCaseId());
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        identifiers.add(auditCase.getEmail());
        resolution.identities().forEach(identity -> {
            identifiers.add(identity.userId());
            identity.identifiers().forEach(identifier -> identifiers.add(identifier.value()));
        });

        Map<String, SourceRecord> matchedRows = new LinkedHashMap<>();
        for (String identifier : identifiers) {
            if (identifier.length() < 3) continue;
            sourceRecords.findTop500ByContentContainingIgnoreCaseOrderBySourceFileAscRowNumberAsc(identifier)
                .forEach(record -> {
                    if (isRelevantAtAuditTime(record, auditCase.getAuditAt())) {
                        matchedRows.putIfAbsent(record.getSourceFile() + "#" + record.getRowNumber(), record);
                    }
                });
        }
        List<Map<String, Object>> rows = matchedRows.values().stream()
            .sorted(Comparator.comparing(SourceRecord::getSourceFile).thenComparingInt(SourceRecord::getRowNumber))
            .limit(MAX_SOURCE_ROWS)
            .map(record -> Map.<String, Object>of(
                "source_file", record.getSourceFile(),
                "row_number", record.getRowNumber(),
                "record", parseRecord(record.getContent())
            ))
            .toList();
        List<Map<String, String>> rules = sourceRecords.findAllBySourceFileOrderByRowNumberAsc("deletion_rules.csv")
            .stream()
            .map(record -> Map.of("record", record.getContent()))
            .toList();
        List<Map<String, String>> manifest = evidence.list().stream()
            .map(file -> Map.of("file_id", file.fileId(), "file_path", file.filePath()))
            .toList();
        List<EvidenceService.TextExcerpt> excerpts = evidence.findTextExcerpts(
            new ArrayList<>(identifiers), MAX_EVIDENCE_CHARACTERS);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("case", Map.of(
            "case_id", auditCase.getCaseId(),
            "email", auditCase.getEmail(),
            "requested_at", stringValue(auditCase.getRequestedAt()),
            "reported_status_claim", stringValue(auditCase.getReportedStatus()),
            "reported_at", stringValue(auditCase.getReportedAt()),
            "audit_at", stringValue(auditCase.getAuditAt()),
            "request_text", stringValue(auditCase.getRequestText())
        ));
        context.put("resolved_identities", resolution.identities());
        context.put("deletion_rules", rules);
        context.put("matching_source_rows", rows);
        context.put("evidence_manifest", manifest);
        context.put("matching_text_evidence_excerpts", excerpts);
        try {
            String serialized = objectMapper.writeValueAsString(context);
            if (serialized.length() > MAX_CONTEXT_CHARACTERS) {
                throw new IllegalStateException("Case evidence exceeds the AI context limit. Narrow the identity matches before analysis.");
            }
            return serialized;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not prepare the local case evidence for analysis.", exception);
        }
    }

    private JsonNode parseRecord(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("An imported source row contains invalid JSON.", exception);
        }
    }

    private boolean isRelevantAtAuditTime(SourceRecord record, LocalDateTime auditAt) {
        if (auditAt == null || !"system_activity.csv".equals(record.getSourceFile())) return true;
        JsonNode row = parseRecord(record.getContent());
        String eventTime = row.path("event_time").asText("");
        if (eventTime.isBlank()) return true;
        try {
            return !OffsetDateTime.parse(eventTime).toLocalDateTime().isAfter(auditAt);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Activity row contains an invalid event_time: " + eventTime, exception);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    public record Analysis(AuditRequest audit, String rationale, List<String> caveats) {
    }
}
