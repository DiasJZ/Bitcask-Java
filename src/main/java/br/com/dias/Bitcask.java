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

    private DataFileWriter writer;
    private Map<String, EntryLocation> index;
    private final Path directory;
    private Path dataDirectory;
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
        this.directory = directory.toAbsolutePath().normalize();
        this.syncOnPut = syncOnPut;
        this.maxFileSize = maxFileSize;
        writerLock = new WriterLock(this.directory);
        try {
            openDataFiles();
        } catch (IOException | RuntimeException error) {
            try {
                writerLock.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private void openDataFiles() throws IOException {
        CompactionState state = CompactionRecovery.recover(directory);
        Path selectedDirectory = state.getDataDirectory();
        List<Long> fileIds = findDataFileIds(selectedDirectory);
        long lastFileId = fileIds.isEmpty() ? 0L : fileIds.get(fileIds.size() - 1);
        long activeFileId = Math.max(lastFileId, state.getFirstWritableFileId());
        Map<String, EntryLocation> loadedIndex = new HashMap<>();
        for (long fileId : fileIds) {
            if (fileId < activeFileId) {
                HintIndexLoader.load(selectedDirectory, fileId, loadedIndex);
            } else {
                try (CaskFile source = CaskFile.openReadOnly(selectedDirectory, fileId)) {
                    source.loadIndex(fileId, loadedIndex);
                }
            }
        }
        DataFileWriter openedWriter = new DataFileWriter(selectedDirectory, activeFileId, maxFileSize);
        dataDirectory = selectedDirectory;
        index = loadedIndex;
        writer = openedWriter;
    }

    private List<Long> findDataFileIds(Path dataDirectory) throws IOException {
        List<Long> fileIds = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(dataDirectory, "*.cask")) {
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
        Entry entry = CaskFile.readAt(dataDirectory, location);
        return Optional.of(entry.getValue());
    }

    public synchronized void sync() throws IOException {
        ensureOpen();
        writer.sync();
    }

    public synchronized void compact() throws IOException {
        ensureOpen();
        writer.sync();
        Path previousDirectory = dataDirectory;
        List<Long> previousFileIds = findDataFileIds(previousDirectory);
        CompactionPlan plan = new CompactionPlan(previousDirectory, index);
        Path output = CompactionWriter.write(directory, plan, maxFileSize);
        CompactionPreparation.prepare(directory, output, plan, maxFileSize);

        try {
            writer.close();
            CompactionRecovery.commit(directory, output);
            openDataFiles();
        } catch (IOException | RuntimeException error) {
            try {
                openDataFiles();
            } catch (IOException | RuntimeException recoveryError) {
                error.addSuppressed(recoveryError);
                closed = true;
                try {
                    writer.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
                try {
                    writerLock.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            throw error;
        }
        removePreviousFiles(previousDirectory, previousFileIds);
    }

    private void removePreviousFiles(Path previousDirectory, List<Long> fileIds) throws IOException {
        Path root = directory.toRealPath();
        Path previous = previousDirectory.toRealPath();
        if (previous.equals(dataDirectory.toRealPath())
                || (!previous.equals(root) && (!root.equals(previous.getParent())
                || !previous.getFileName().toString().startsWith(".compaction-")))) {
            throw new IOException("Invalid directory for old data cleanup");
        }
        for (long fileId : fileIds) {
            Files.deleteIfExists(previous.resolve(fileId + ".cask"));
            Files.deleteIfExists(previous.resolve(fileId + ".hint"));
        }
        if (!previous.equals(root)) {
            Files.deleteIfExists(previous.resolve("READY"));
            boolean empty;
            try (var paths = Files.list(previous)) {
                empty = paths.findAny().isEmpty();
            }
            if (empty) {
                Files.delete(previous);
            }
        }
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
