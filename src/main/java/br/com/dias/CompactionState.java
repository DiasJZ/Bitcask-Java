package br.com.dias;

import java.nio.file.Path;

final class CompactionState {
    private final Path dataDirectory;
    private final long firstWritableFileId;

    CompactionState(Path dataDirectory, long firstWritableFileId) {
        this.dataDirectory = dataDirectory;
        this.firstWritableFileId = firstWritableFileId;
    }

    Path getDataDirectory() {
        return dataDirectory;
    }

    long getFirstWritableFileId() {
        return firstWritableFileId;
    }
}
