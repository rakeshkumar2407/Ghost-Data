package com.northstar.audit.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceService {
    private final Path datasetDirectory;

    public EvidenceService(@Value("${app.dataset-directory:../dataset}") String datasetDirectory) {
        this.datasetDirectory = Path.of(datasetDirectory).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<EvidenceFile> list() {
        Path manifest = datasetDirectory.resolve("evidence_files.csv");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("The bundled evidence manifest is missing.");
        }
        try (var reader = Files.newBufferedReader(manifest);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build().parse(reader)) {
            return parser.getRecords().stream().map(row -> {
                String filePath = row.get("file_path");
                Path resolved = resolveEvidencePath(filePath);
                String filename = Path.of(filePath).getFileName().toString();
                String category = Path.of(filePath).getParent() == null
                    ? "Evidence" : title(Path.of(filePath).getParent().getFileName().toString());
                try {
                    return new EvidenceFile(
                        row.get("file_id"),
                        filePath,
                        filename,
                        row.get("origin"),
                        row.get("captured_at"),
                        category,
                        mediaType(filename),
                        Files.size(resolved)
                    );
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not inspect evidence file " + filePath + ".", exception);
                }
            }).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the bundled evidence manifest.", exception);
        }
    }

    public EvidenceContent read(String fileId) {
        EvidenceFile evidence = list().stream()
            .filter(item -> item.fileId().equals(fileId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Evidence file not found: " + fileId));
        Path path = resolveEvidencePath(evidence.filePath());
        try {
            return new EvidenceContent(evidence, Files.readAllBytes(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read evidence file " + evidence.fileName() + ".", exception);
        }
    }

    public List<TextExcerpt> findTextExcerpts(List<String> identifiers, int maxCharacters) {
        List<String> searchTerms = identifiers.stream()
            .filter(value -> value != null && value.length() >= 3)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
        List<TextExcerpt> excerpts = new ArrayList<>();
        int remainingCharacters = maxCharacters;
        for (EvidenceFile file : list()) {
            if (remainingCharacters <= 0 || !isTextEvidence(file.fileName())) continue;
            Path path = resolveEvidencePath(file.filePath());
            try {
                List<String> lines = Files.readAllLines(path);
                List<String> matches = new ArrayList<>();
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).toLowerCase(Locale.ROOT);
                    if (searchTerms.stream().anyMatch(line::contains)) {
                        int start = Math.max(0, index - 1);
                        int end = Math.min(lines.size(), index + 2);
                        for (int contextIndex = start; contextIndex < end; contextIndex++) {
                            String contextLine = lines.get(contextIndex);
                            if (!matches.contains(contextLine)) matches.add(contextLine);
                        }
                    }
                }
                if (!matches.isEmpty()) {
                    String excerpt = String.join(System.lineSeparator(), matches);
                    if (excerpt.length() > remainingCharacters) excerpt = excerpt.substring(0, remainingCharacters);
                    excerpts.add(new TextExcerpt(file.fileId(), file.filePath(), excerpt));
                    remainingCharacters -= excerpt.length();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read evidence file " + file.fileName() + ".", exception);
            }
        }
        return excerpts;
    }

    private Path resolveEvidencePath(String relativePath) {
        Path resolved = datasetDirectory.resolve(relativePath).normalize();
        if (!resolved.startsWith(datasetDirectory) || !resolved.startsWith(datasetDirectory.resolve("evidence"))) {
            throw new IllegalArgumentException("Evidence path is outside the evidence directory.");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Evidence file is missing: " + relativePath);
        }
        try {
            Path realDataset = datasetDirectory.toRealPath();
            Path realEvidence = realDataset.resolve("evidence").toRealPath();
            Path realPath = resolved.toRealPath();
            if (!realPath.startsWith(realEvidence)) {
                throw new IllegalArgumentException("Evidence path resolves outside the evidence directory.");
            }
            return realPath;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not resolve evidence file: " + relativePath, exception);
        }
    }

    private static String mediaType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF_VALUE;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON_VALUE;
        if (lower.endsWith(".sql")) return "application/sql";
        if (lower.endsWith(".log") || lower.endsWith(".txt")) return MediaType.TEXT_PLAIN_VALUE;
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static boolean isTextEvidence(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".sql") || lower.endsWith(".log") || lower.endsWith(".txt");
    }

    private static String title(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    public record EvidenceFile(
        String fileId,
        String filePath,
        String fileName,
        String origin,
        String capturedAt,
        String category,
        String mediaType,
        long size
    ) {
    }

    public record EvidenceContent(EvidenceFile file, byte[] content) {
    }

    public record TextExcerpt(String fileId, String filePath, String content) {
    }
}
