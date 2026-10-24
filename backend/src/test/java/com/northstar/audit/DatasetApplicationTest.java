package com.northstar.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DatasetApplicationTest {
    @Autowired
    private TestRestTemplate http;

    @Test
    void loadsBundledRequestsAndServesManifestBackedEvidence() {
        JsonNode dashboard = http.getForObject("/api/dashboard", JsonNode.class);
        assertEquals(518, dashboard.get("totalCases").asInt());
        assertEquals(515, dashboard.get("pendingCases").asInt());
        assertEquals(3, dashboard.get("notApplicableCases").asInt());
        assertEquals(5, dashboard.get("sourceFiles").asInt());

        JsonNode nonDeletionCase = http.getForObject("/api/cases/case_534", JsonNode.class);
        assertEquals("NOT_REQUESTED", nonDeletionCase.get("deletionIntent").asText());
        assertEquals("NOT_APPLICABLE", nonDeletionCase.get("deletionStatus").asText());
        assertEquals(0, nonDeletionCase.get("steps").size());

        for (String caseId : List.of("case_536", "case_542")) {
            JsonNode explicitNoDeleteCase = http.getForObject("/api/cases/" + caseId, JsonNode.class);
            assertEquals("NOT_REQUESTED", explicitNoDeleteCase.get("deletionIntent").asText());
            assertEquals("NOT_APPLICABLE", explicitNoDeleteCase.get("deletionStatus").asText());
            assertEquals(0, explicitNoDeleteCase.get("steps").size());
        }

        JsonNode identity = http.getForObject("/api/cases/case_33/identities", JsonNode.class);
        assertEquals("ingrid.dahl@outlook.com", identity.get("requestedEmail").asText());
        assertEquals("user_18", identity.get("identities").get(0).get("userId").asText());
        assertTrue(identity.get("identities").get(0).get("identifiers").size() >= 4);

        List<JsonNode> evidence = http.exchange("/api/evidence", HttpMethod.GET, null,
            new ParameterizedTypeReference<List<JsonNode>>() {}).getBody();
        assertTrue(evidence != null && evidence.size() >= 90);

        String fileId = evidence.stream()
            .filter(file -> file.get("mediaType").asText().equals("image/png"))
            .findFirst().orElseThrow().get("fileId").asText();
        byte[] image = http.getForObject("/api/evidence/" + fileId + "/content", byte[].class);
        assertTrue(image != null && image.length > 4);
        assertEquals((byte) 0x89, image[0]);
        assertEquals('P', image[1]);

        Map<String, Object> audit = Map.of(
            "deletionStatus", "INCOMPLETE",
            "steps", List.of(
                step(1, "VERIFICATION", "VERIFY_RECORD_ABSENT", "ABSENT", ""),
                step(2, "REMEDIATION", "DELETE_RECORD", "ABSENT", ""),
                step(3, "FINDING", "REPORT_REMAINING_RECORD", "PRESENT", "evidence_01")
            )
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode saved = http.exchange("/api/cases/case_33/audit", HttpMethod.PUT,
            new HttpEntity<>(audit, headers), JsonNode.class).getBody();
        assertEquals("INCOMPLETE", saved.get("deletionStatus").asText());
        assertEquals("FINDING", saved.get("steps").get(0).get("phase").asText());
        assertEquals("REMEDIATION", saved.get("steps").get(1).get("phase").asText());
        assertEquals("VERIFICATION", saved.get("steps").get(2).get("phase").asText());
        assertEquals("", saved.get("steps").get(1).get("evidenceId").asText());
    }

    @Test
    void filtersRequestsBySavedAuditOutcomeAndPendingReview() {
        JsonNode pending = http.getForObject("/api/cases?status=PENDING&page=0&size=20", JsonNode.class);
        assertEquals(515, pending.get("totalElements").asInt());
        assertTrue(pending.get("content").get(0).get("deletionStatus").isNull());

        JsonNode notApplicable = http.getForObject(
            "/api/cases?status=NOT_APPLICABLE&page=0&size=20", JsonNode.class);
        assertEquals(3, notApplicable.get("totalElements").asInt());
        assertEquals("NOT_APPLICABLE", notApplicable.get("content").get(0).get("deletionStatus").asText());

        JsonNode complete = http.getForObject("/api/cases?status=COMPLETE&page=0&size=20", JsonNode.class);
        assertEquals(0, complete.get("totalElements").asInt());
    }

    @Test
    void reportsMissingAiConfigurationWithoutBreakingTheWorkspace() {
        var response = http.postForEntity("/api/cases/case_33/analyze", null, JsonNode.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().get("error").asText().contains("OPENAI_API_KEY"));
    }

    @Test
    void exposesPagedAdminDataAndCustomerRelationshipsFromImportedRows() {
        JsonNode overview = http.getForObject("/api/admin/overview", JsonNode.class);
        assertEquals(850, overview.get("customerProfiles").asInt());
        assertEquals(4011, overview.get("systemActivities").asInt());
        assertEquals(6, overview.get("dataTables").asInt());

        JsonNode tables = http.getForObject("/api/admin/tables", JsonNode.class);
        assertTrue(tables.toString().contains("deletion_requests.csv"));
        assertTrue(tables.toString().contains("user_identities.csv"));
        assertTrue(tables.toString().contains("system_activity.csv"));

        JsonNode requestRows = http.getForObject(
            "/api/admin/tables/deletion_requests.csv?page=0&size=20", JsonNode.class);
        assertEquals(518, requestRows.get("totalElements").asInt());
        assertTrue(requestRows.get("content").get(0).get("data").has("request_text"));
        JsonNode searchedRequests = http.getForObject(
            "/api/admin/tables/deletion_requests.csv?search=promotional&page=0&size=20", JsonNode.class);
        assertEquals(1, searchedRequests.get("totalElements").asInt());
        assertEquals("case_534", searchedRequests.get("content").get(0).get("data").get("case_id").asText());

        JsonNode customerRows = http.getForObject(
            "/api/admin/tables/user_identities.csv?search=user_11&page=0&size=100", JsonNode.class);
        assertTrue(customerRows.get("totalElements").asInt() >= 1);
        assertTrue(customerRows.get("content").findValues("user_id").stream()
            .anyMatch(userId -> userId.asText().equals("user_11")));

        JsonNode customer = http.getForObject("/api/admin/customers/user_11", JsonNode.class);
        assertEquals("user_11", customer.get("profile").get("user_id").asText());
        assertTrue(customer.get("systemActivity").toString().contains("user_11"));
        assertTrue(customer.has("identityHistory"));
        assertTrue(customer.has("deletionRequests"));
    }

    private static Map<String, Object> step(int number, String phase, String operation, String state, String evidenceId) {
        return Map.of(
            "stepNo", number,
            "phase", phase,
            "systemId", "account_service",
            "resourceId", "account_003",
            "operation", operation,
            "state", state,
            "evidenceId", evidenceId
        );
    }
}
