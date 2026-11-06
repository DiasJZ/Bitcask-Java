package br.com.dias;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class Bitcask implements AutoCloseable {
    private static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024L;

    private final DataFileWriter writer;
    private final Map<String, EntryLocation> index = new HashMap<>();
    private final Path directory;
    private final WriterLock writerLock;
    private final boolean syncOnPut;
    private boolean closed;

    public Bitcask(Path directory) throws IOException {
        this(directory, false);
    }

    public Bitcask(Path directory, boolean syncOnPut) throws IOException {
        this(directory, syncOnPut, DEFAULT_MAX_FILE_SIZE);
    }

    public Bitcask(Path directory, boolean syncOnPut, long maxFileSize) throws IOException {
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("Maximum file size must be greater than zero");
        }
        this.directory = directory;
        this.syncOnPut = syncOnPut;
        writerLock = new WriterLock(directory);
        try {
            List<Long> fileIds = findDataFileIds();
            for (long fileId : fileIds) {
                try (CaskFile source = CaskFile.openReadOnly(directory, fileId)) {
                    source.loadIndex(fileId, index);
                }
            }
            long activeFileId = fileIds.isEmpty() ? 0L : fileIds.get(fileIds.size() - 1);
            writer = new DataFileWriter(directory, activeFileId, maxFileSize);
        } catch (IOException | RuntimeException error) {
            try {
                writerLock.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private List<Long> findDataFileIds() throws IOException {
        List<Long> fileIds = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "*.cask")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9]+\\.cask")) {
                    continue;
                }
                long fileId;
                try {
                    fileId = Long.parseLong(name.substring(0, name.length() - 5));
                } catch (NumberFormatException error) {
                    throw new IOException("Invalid data file ID: " + name, error);
                }
                if (!name.equals(fileId + ".cask") || !Files.isRegularFile(path)) {
                    throw new IOException("Invalid data file: " + name);
                }
                fileIds.add(fileId);
            }
        }
        fileIds.sort(Long::compareTo);
        return fileIds;
    }

    public synchronized void put(String key, String value) throws IOException {
        ensureOpen();
        Objects.requireNonNull(value, "Value must not be null");
        Entry entry = new Entry(Instant.now().getEpochSecond(), key, value);
        EntryLocation location = writer.append(entry);
        index.put(key, location);
        if (syncOnPut) {
            writer.sync();
        }
    }

    public synchronized void delete(String key) throws IOException {
        ensureOpen();
        Entry entry = Entry.tombstone(Instant.now().getEpochSecond(), key);
        writer.append(entry);
        index.remove(key);
        if (syncOnPut) {
            writer.sync();
        }
    }

    public synchronized Optional<String> get(String key) throws IOException {
        ensureOpen();
        EntryLocation location = index.get(key);
        if (location == null) {
            return Optional.empty();
        }
        Entry entry = CaskFile.readAt(directory, location);
        return Optional.of(entry.getValue());
    }

    public synchronized void sync() throws IOException {
        ensureOpen();
        writer.sync();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Bitcask is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (writerLock) {
            writer.close();
        }
    }
}
