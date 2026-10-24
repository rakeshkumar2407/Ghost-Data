package com.northstar.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.SourceRecord;
import com.northstar.audit.repository.AuditCaseRepository;
import com.northstar.audit.repository.SourceRecordRepository;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasetImportService {
    private final AuditCaseRepository cases;
    private final SourceRecordRepository sourceRecords;
    private final ObjectMapper objectMapper;

    public DatasetImportService(AuditCaseRepository cases, SourceRecordRepository sourceRecords, ObjectMapper objectMapper) {
        this.cases = cases;
        this.sourceRecords = sourceRecords;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImportResult importFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Select at least one CSV file.");
        }
        List<CsvInput> inputs;
        try {
            inputs = files.stream()
                .filter(file -> file.getOriginalFilename() != null
                    && file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv"))
                .map(file -> {
                    try {
                        return new CsvInput(fileName(file), file.getBytes());
                    } catch (IOException exception) {
                        throw new IllegalArgumentException("Could not read " + fileName(file) + ": " + exception.getMessage());
                    }
                })
                .toList();
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
        return importCsvInputs(inputs);
    }

    @Transactional
    public ImportResult importBundled(Path datasetDirectory) {
        if (!Files.isDirectory(datasetDirectory)) {
            throw new IllegalArgumentException("Bundled dataset directory is not available: " + datasetDirectory.toAbsolutePath());
        }
        try (var paths = Files.list(datasetDirectory)) {
            List<CsvInput> inputs = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(item -> item.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                .sorted()
                .toList()) {
                inputs.add(new CsvInput(path.getFileName().toString(), Files.readAllBytes(path)));
            }
            return importCsvInputs(inputs);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the bundled dataset: " + exception.getMessage(), exception);
        }
    }

    private ImportResult importCsvInputs(List<CsvInput> files) {
        CsvInput requestFile = files.stream()
            .filter(file -> "deletion_requests.csv".equalsIgnoreCase(file.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("The upload must include deletion_requests.csv."));

        List<CSVRecord> requestRows = parse(requestFile.name(), requestFile.content());
        if (requestRows.isEmpty()) {
            throw new IllegalArgumentException("deletion_requests.csv contains no request rows.");
        }
        List<AuditCase> parsedCases = new ArrayList<>();
        for (CSVRecord row : requestRows) {
            String caseId = required(row, "case_id");
            String email = required(row, "email");
            AuditCase existing = cases.findById(caseId).orElse(null);
            if (existing == null) {
                parsedCases.add(new AuditCase(caseId, email, date(row, "requested_at"), value(row, "reported_status"),
                    date(row, "reported_at"), date(row, "audit_at"), value(row, "request_text")));
            } else {
                existing.updateRequest(date(row, "requested_at"), value(row, "reported_status"),
                    date(row, "reported_at"), date(row, "audit_at"), value(row, "request_text"));
                parsedCases.add(existing);
            }
        }
        cases.saveAll(parsedCases);
        for (AuditCase auditCase : parsedCases) {
            if ("NOT_REQUESTED".equals(DeletionIntentResolver.resolve(auditCase.getRequestText()).status())) {
                auditCase.saveAudit("NOT_APPLICABLE", List.of());
            }
        }
        cases.saveAll(parsedCases);

        int recordCount = 0;
        int importedFiles = 0;
        for (CsvInput file : files) {
            String name = file.name();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")
                || "deletion_requests.csv".equalsIgnoreCase(name)
                || "sample_deletion_requests.csv".equalsIgnoreCase(name)
                || "sample_output.csv".equalsIgnoreCase(name)
                || "output.csv".equalsIgnoreCase(name)) {
                continue;
            }
            List<CSVRecord> rows = parse(name, file.content());
            sourceRecords.deleteBySourceFile(name);
            List<SourceRecord> records = new ArrayList<>(rows.size());
            for (CSVRecord row : rows) {
                Map<String, String> values = new LinkedHashMap<>();
                row.toMap().forEach(values::put);
                records.add(new SourceRecord(name, (int) row.getRecordNumber(), toJson(values)));
            }
            sourceRecords.saveAll(records);
            recordCount += records.size();
            importedFiles++;
        }
        return new ImportResult(parsedCases.size(), importedFiles, recordCount);
    }

    private List<CSVRecord> parse(String fileName, byte[] bytes) {
        try {
            int start = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
            try (InputStreamReader reader = new InputStreamReader(
                     new ByteArrayInputStream(bytes, start, bytes.length - start), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                 .setHeader()
                 .setSkipHeaderRecord(true)
                 .setIgnoreHeaderCase(true)
                 .setTrim(true)
                 .build()
                 .parse(reader)) {
                if (parser.getHeaderMap().isEmpty()) {
                     throw new IllegalArgumentException(fileName + " is missing its header row.");
                }
                return parser.getRecords();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read " + fileName + ": " + exception.getMessage());
        }
    }

    private String toJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode an imported CSV row.", exception);
        }
    }

    private static String required(CSVRecord row, String column) {
        String value = value(row, column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required value '" + column + "' in CSV row " + row.getRecordNumber() + ".");
        }
        return value;
    }

    private static String value(CSVRecord row, String column) {
        try {
            String value = row.get(column);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("CSV is missing the required column '" + column + "'.");
        }
    }

    private static LocalDateTime date(CSVRecord row, String column) {
        String value = value(row, column);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return java.time.LocalDate.parse(value).atStartOfDay();
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException("Invalid date in column '" + column + "' at CSV row "
                        + row.getRecordNumber() + ".");
                }
            }
        }
    }

    private static String fileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("Uploaded file name is missing.");
        }
        String name = java.nio.file.Path.of(original.replace('\\', '/')).getFileName().toString();
        if (name.contains("..")) {
            throw new IllegalArgumentException("Invalid uploaded file name.");
        }
        return name;
    }

    public record ImportResult(int casesImported, int csvFilesImported, int sourceRowsImported) {
    }

    private record CsvInput(String name, byte[] content) {
    }
}
