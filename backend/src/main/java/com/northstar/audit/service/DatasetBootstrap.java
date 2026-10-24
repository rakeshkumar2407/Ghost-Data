package com.northstar.audit.service;

import com.northstar.audit.repository.AuditCaseRepository;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatasetBootstrap implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetBootstrap.class);

    private final AuditCaseRepository cases;
    private final DatasetImportService importer;
    private final Path datasetDirectory;

    public DatasetBootstrap(
        AuditCaseRepository cases,
        DatasetImportService importer,
        @Value("${app.dataset-directory:../dataset}") String datasetDirectory
    ) {
        this.cases = cases;
        this.importer = importer;
        this.datasetDirectory = Path.of(datasetDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (cases.count() != 0) {
            return;
        }
        if (!java.nio.file.Files.isDirectory(datasetDirectory)) {
            LOGGER.info("No bundled dataset found at {}; waiting for a CSV import.", datasetDirectory);
            return;
        }
        DatasetImportService.ImportResult result = importer.importBundled(datasetDirectory);
        LOGGER.info("Loaded bundled dataset: {} cases, {} source files, {} source rows.",
            result.casesImported(), result.csvFilesImported(), result.sourceRowsImported());
    }
}
