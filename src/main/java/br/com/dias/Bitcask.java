package br.com.dias;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class Bitcask implements AutoCloseable {
    private static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024L;

    private final CaskFile file;
    private final Map<String, Long> index;
    private final WriterLock writerLock;
    private final boolean syncOnPut;
    private final long maxFileSize;
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
        this.maxFileSize = maxFileSize;
        this.syncOnPut = syncOnPut;
        writerLock = new WriterLock(directory);
        CaskFile openedFile = null;
        try {
            openedFile = new CaskFile(directory.resolve("0.cask"));
            index = openedFile.loadIndex();
            file = openedFile;
        } catch (IOException | RuntimeException error) {
            if (openedFile != null) {
                try {
                    openedFile.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            try {
                writerLock.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    public synchronized void put(String key, String value) throws IOException {
        ensureOpen();
        Objects.requireNonNull(value, "Value must not be null");
        Entry entry = new Entry(Instant.now().getEpochSecond(), key, value);
        long offset = file.append(entry);
        index.put(key, offset);
        if (syncOnPut) {
            file.sync();
        }
    }

    public synchronized void delete(String key) throws IOException {
        ensureOpen();
        Entry entry = Entry.tombstone(Instant.now().getEpochSecond(), key);
        file.append(entry);
        index.remove(key);
        if (syncOnPut) {
            file.sync();
        }
    }

    public synchronized Optional<String> get(String key) throws IOException {
        ensureOpen();
        Long offset = index.get(key);
        if (offset == null) {
            return Optional.empty();
        }
        return Optional.of(file.read(offset).getValue());
    }

    public synchronized void sync() throws IOException {
        ensureOpen();
        file.sync();
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
        try (writerLock; file) {
            file.sync();
        }
    }
}
