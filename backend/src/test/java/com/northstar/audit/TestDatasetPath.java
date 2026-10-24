package com.northstar.audit;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TestDatasetPath {
    private TestDatasetPath() {
    }

    public static Path resolve() {
        String configured = System.getenv("DATASET_DIRECTORY");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) return path;
        }
        for (Path path : new Path[] {Path.of("..", "dataset"), Path.of("..", "..", "dataset")}) {
            Path resolved = path.toAbsolutePath().normalize();
            if (Files.isDirectory(resolved)) return resolved;
        }
        throw new IllegalStateException("Could not find the challenge dataset for tests.");
    }
}
