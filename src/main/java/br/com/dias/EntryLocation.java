package br.com.dias;

public class EntryLocation {
    private final long fileId;
    private final long offset;

    public EntryLocation(long fileId, long offset) {
        if (fileId < 0 || offset < 0) {
            throw new IllegalArgumentException("File ID and offset must not be negative");
        }
        this.fileId = fileId;
        this.offset = offset;
    }

    public long getFileId() {
        return fileId;
    }

    public long getOffset() {
        return offset;
    }
}
