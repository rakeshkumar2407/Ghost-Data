package com.northstar.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.repository.SourceRecordRepository;
import org.springframework.stereotype.Service;

@Service
public class EvidenceReferenceService {
    private final SourceRecordRepository sourceRecords;
    private final EvidenceService evidence;
    private final ObjectMapper objectMapper;

    public EvidenceReferenceService(
        SourceRecordRepository sourceRecords,
        EvidenceService evidence,
        ObjectMapper objectMapper
    ) {
        this.sourceRecords = sourceRecords;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
    }

    public boolean exists(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) return false;
        if (evidenceId.startsWith("evidence_")) {
            return evidence.list().stream().anyMatch(file -> file.fileId().equals(evidenceId));
        }
        String field = evidenceId.startsWith("activity_") ? "activity_id"
            : evidenceId.startsWith("audit_") ? "audit_id" : null;
        if (field == null) return false;
        return sourceRecords.findTop500ByContentContainingIgnoreCaseOrderBySourceFileAscRowNumberAsc(evidenceId)
            .stream()
            .anyMatch(record -> hasExactReference(record.getContent(), field, evidenceId));
    }

    private boolean hasExactReference(String content, String field, String evidenceId) {
        try {
            return evidenceId.equals(objectMapper.readTree(content).path(field).asText());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("An imported source row contains invalid JSON.", exception);
        }
    }
}
