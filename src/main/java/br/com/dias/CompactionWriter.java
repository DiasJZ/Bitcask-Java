package br.com.dias;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class CompactionWriter {
    static Path write(Path directory, CompactionPlan plan, long maxFileSize) throws IOException {
        Objects.requireNonNull(plan, "Compaction plan must not be null");
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("Maximum file size must be greater than zero");
        }
        List<String> keys = new ArrayList<>(plan.getKeys());
        keys.sort(String::compareTo);
        Path temporaryDirectory = Files.createTempDirectory(directory, ".compaction-");
        try {
            long lastFileId;
            try (DataFileWriter writer = new DataFileWriter(temporaryDirectory, 0, maxFileSize)) {
                for (String key : keys) {
                    writer.append(plan.readEntry(key));
                }
                lastFileId = writer.getActiveFileId();
            }
            HintFileWriter.write(temporaryDirectory, lastFileId);
            return temporaryDirectory;
        } catch (IOException | RuntimeException error) {
            try {
                removeTemporaryFiles(temporaryDirectory);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private static void removeTemporaryFiles(Path directory) throws IOException {
        try (var files = Files.newDirectoryStream(directory, "*.{cask,hint}")) {
            for (Path file : files) {
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }
}
