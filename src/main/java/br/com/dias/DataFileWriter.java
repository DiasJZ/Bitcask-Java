package br.com.dias;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DataFileWriter implements AutoCloseable {
    private final Path directory;
    private final long maxFileSize;
    private CaskFile file;
    private long activeFileId;
    private boolean closed;

    DataFileWriter(Path directory, long activeFileId, long maxFileSize) throws IOException {
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("Maximum file size must be greater than zero");
        }
        this.directory = directory;
        this.activeFileId = activeFileId;
        this.maxFileSize = maxFileSize;
        file = new CaskFile(directory, activeFileId);
    }

    EntryLocation append(Entry entry) throws IOException {
        ensureOpen();
        byte[] record = entry.encode();
        if (record.length > maxFileSize) {
            throw new IOException("Record exceeds maximum file size");
        }
        long currentSize = file.size();
        if (currentSize > 0 && record.length > maxFileSize - currentSize) {
            rotate();
        }
        long offset = file.append(record);
        return new EntryLocation(activeFileId, offset);
    }

    long getActiveFileId() {
        return activeFileId;
    }

    void sync() throws IOException {
        ensureOpen();
        file.sync();
    }

    private void rotate() throws IOException {
        if (activeFileId == Long.MAX_VALUE) {
            throw new IOException("No more file IDs available");
        }
        file.sync();
        long nextFileId = activeFileId + 1;
        Path nextPath = directory.resolve(nextFileId + ".cask");
        Files.createFile(nextPath);
        CaskFile nextFile;
        try {
            nextFile = new CaskFile(nextPath);
        } catch (IOException | RuntimeException error) {
            try {
                Files.delete(nextPath);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        try {
            file.close();
        } catch (IOException error) {
            closed = true;
            try {
                nextFile.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
        file = nextFile;
        activeFileId = nextFileId;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Data file writer is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        CaskFile currentFile = file;
        try (currentFile) {
            currentFile.sync();
        }
    }
}
