package com.northstar.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.repository.AuditCaseRepository;
import com.northstar.audit.repository.SourceRecordRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class AuditAnalysisServiceTest {
    @Mock private AuditCaseRepository cases;
    @Mock private SourceRecordRepository sourceRecords;
    @Mock private IdentityResolutionService identities;
    @Mock private EvidenceService evidence;
    @Mock private AuditService auditService;
    @Mock private RestClient.Builder restClientBuilder;
    @Mock private RestClient restClient;

    private AuditAnalysisService service;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
        when(restClientBuilder.build()).thenReturn(restClient);
        service = new AuditAnalysisService(cases, sourceRecords, identities, evidence, auditService,
            new ObjectMapper(), restClientBuilder, "openai", "", "", "", "");
    }

    @Test
    void classifiesExplicitNonDeletionWithoutCallingProvider() {
        when(cases.findById("case_no_delete")).thenReturn(Optional.of(new AuditCase(
            "case_no_delete", "person@example.test", null, "NOT_STARTED", null, null,
            "Please stop these emails. I am not asking for deletion."
        )));

        AuditAnalysisService.Analysis result = service.analyze("case_no_delete");

        assertEquals("NOT_APPLICABLE", result.audit().deletionStatus());
        assertTrue(result.audit().steps().isEmpty());
        verifyNoInteractions(restClient);
    }

    @Test
    void reportsMissingProviderKeyForDeletionRequests() {
        when(cases.findById("case_delete")).thenReturn(Optional.of(new AuditCase(
            "case_delete", "person@example.test", null, "COMPLETE", null, null,
            "Please permanently delete my account."
        )));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.analyze("case_delete"));

        assertTrue(error.getMessage().contains("OPENAI_API_KEY"));
        verifyNoInteractions(restClient);
    }

    @Test
    void selectsGeminiFreeTierDefaultsAndReportsItsMissingKey() {
        AuditAnalysisService geminiService = new AuditAnalysisService(cases, sourceRecords, identities, evidence, auditService,
            new ObjectMapper(), restClientBuilder, "gemini", "", "", "", "");
        when(cases.findById("case_delete")).thenReturn(Optional.of(new AuditCase(
            "case_delete", "person@example.test", null, "COMPLETE", null, null,
            "Please permanently delete my account."
        )));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> geminiService.analyze("case_delete"));

        assertTrue(error.getMessage().contains("GEMINI_API_KEY"));
        verify(restClientBuilder).baseUrl("https://generativelanguage.googleapis.com/v1beta/openai");
        verifyNoInteractions(restClient);
    }
}
