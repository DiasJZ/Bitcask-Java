package br.com.dias;

import java.util.List;

final class HintFile {
    private final long fileId;
    private final long dataSize;
    private final long dataChecksum;
    private final List<HintEntry> entries;

    HintFile(long fileId, long dataSize, long dataChecksum, List<HintEntry> entries) {
        this.fileId = fileId;
        this.dataSize = dataSize;
        this.dataChecksum = dataChecksum;
        this.entries = List.copyOf(entries);
    }

    long getFileId() {
        return fileId;
    }

    long getDataSize() {
        return dataSize;
    }

    long getDataChecksum() {
        return dataChecksum;
    }

    List<HintEntry> getEntries() {
        return entries;
    }
}
