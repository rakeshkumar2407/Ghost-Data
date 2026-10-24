package com.northstar.audit.web;

import com.northstar.audit.repository.SourceRecordRepository;
import com.northstar.audit.service.AuditService;
import com.northstar.audit.service.AuditAnalysisService;
import com.northstar.audit.service.AdminWorkspaceService;
import com.northstar.audit.service.CsvExportService;
import com.northstar.audit.service.DatasetImportService;
import com.northstar.audit.service.EvidenceService;
import com.northstar.audit.service.EvidenceService.EvidenceContent;
import com.northstar.audit.service.EvidenceService.EvidenceFile;
import com.northstar.audit.service.IdentityResolutionService;
import com.northstar.audit.service.IdentityResolutionService.IdentityResolution;
import com.northstar.audit.service.DatasetImportService.ImportResult;
import com.northstar.audit.web.AuditDtos.AuditRequest;
import com.northstar.audit.web.AuditDtos.CaseDetail;
import com.northstar.audit.web.AuditDtos.SourceRecordResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AuditController {
    private final AuditService auditService;
    private final AuditAnalysisService analysisService;
    private final DatasetImportService importService;
    private final SourceRecordRepository sourceRecords;
    private final CsvExportService exportService;
    private final EvidenceService evidenceService;
    private final IdentityResolutionService identityResolutionService;
    private final AdminWorkspaceService adminWorkspaceService;

    public AuditController(AuditService auditService, AuditAnalysisService analysisService, DatasetImportService importService,
                           SourceRecordRepository sourceRecords, CsvExportService exportService,
                           EvidenceService evidenceService, IdentityResolutionService identityResolutionService,
                           AdminWorkspaceService adminWorkspaceService) {
        this.auditService = auditService;
        this.analysisService = analysisService;
        this.importService = importService;
        this.sourceRecords = sourceRecords;
        this.exportService = exportService;
        this.evidenceService = evidenceService;
        this.identityResolutionService = identityResolutionService;
        this.adminWorkspaceService = adminWorkspaceService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return auditService.dashboard(sourceRecords.countSourceFiles());
    }

    @GetMapping("/admin/overview")
    public AdminWorkspaceService.Overview adminOverview() {
        return adminWorkspaceService.overview();
    }

    @GetMapping("/admin/tables")
    public List<AdminWorkspaceService.TableSummary> adminTables() {
        return adminWorkspaceService.tables();
    }

    @GetMapping("/admin/tables/{sourceFile}")
    public Page<AdminWorkspaceService.SourceRow> adminTableRows(
        @PathVariable String sourceFile,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return adminWorkspaceService.tableRows(sourceFile, search, page, size);
    }

    @GetMapping("/admin/customers/{userId}")
    public AdminWorkspaceService.CustomerDetail adminCustomer(@PathVariable String userId) {
        return adminWorkspaceService.customer(userId);
    }

    @GetMapping("/cases")
    public Page<?> listCases(@RequestParam(required = false) String search,
                             @RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return auditService.list(search, status, page, size);
    }

    @GetMapping("/cases/{caseId}")
    public CaseDetail getCase(@PathVariable String caseId) {
        return auditService.get(caseId);
    }

    @PostMapping("/cases/{caseId}/analyze")
    public AuditAnalysisService.Analysis analyzeCase(@PathVariable String caseId) {
        return analysisService.analyze(caseId);
    }

    @GetMapping("/cases/{caseId}/identities")
    public IdentityResolution resolveIdentities(@PathVariable String caseId) {
        return identityResolutionService.resolve(caseId);
    }

    @GetMapping("/source-records")
    public List<SourceRecordResponse> searchSourceRecords(@RequestParam String search) {
        if (search == null || search.trim().length() < 3) {
            throw new IllegalArgumentException("Enter at least 3 characters to search imported source records.");
        }
        return sourceRecords.findTop100ByContentContainingIgnoreCaseOrderBySourceFileAscRowNumberAsc(search.trim())
            .stream().map(SourceRecordResponse::from).collect(Collectors.toList());
    }

    @GetMapping("/evidence")
    public List<EvidenceFile> listEvidence() {
        return evidenceService.list();
    }

    @GetMapping("/evidence/{fileId}/content")
    public ResponseEntity<ByteArrayResource> readEvidence(@PathVariable String fileId) {
        EvidenceContent evidence = evidenceService.read(fileId);
        MediaType type = MediaType.parseMediaType(evidence.file().mediaType());
        return ResponseEntity.status(HttpStatus.OK)
            .contentType(type)
            .contentLength(evidence.content().length)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(evidence.file().fileName(), StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(new ByteArrayResource(evidence.content()));
    }

    @PutMapping("/cases/{caseId}/audit")
    public CaseDetail saveAudit(@PathVariable String caseId, @Valid @org.springframework.web.bind.annotation.RequestBody AuditRequest request) {
        return auditService.saveAudit(caseId, request);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importFiles(@RequestPart("files") List<MultipartFile> files) {
        return importService.importFiles(files);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        byte[] content = exportService.export().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("output.csv").build().toString())
            .body(content);
    }
}
