package com.northstar.audit.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.northstar.audit.TestDatasetPath;
import org.junit.jupiter.api.Test;

class EvidenceServiceTest {
    private final EvidenceService evidence = new EvidenceService(TestDatasetPath.resolve().toString());

    @Test
    void listsManifestEvidenceAndReadsLocalImageContent() {
        var files = evidence.list();
        assertFalse(files.isEmpty());
        var image = files.stream().filter(file -> file.mediaType().equals("image/png")).findFirst().orElseThrow();

        var content = evidence.read(image.fileId()).content();

        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, new byte[] {
            content[0], content[1], content[2], content[3]
        });
    }

    @Test
    void rejectsEvidenceIdsThatAreNotInTheManifest() {
        assertThrows(IllegalArgumentException.class, () -> evidence.read("../../application.yml"));
    }
}
