package br.com.dias;

final class HintEntry {
    private final long timestamp;
    private final long offset;
    private final String key;
    private final long valueSize;

    HintEntry(long timestamp, long offset, String key, long valueSize) {
        this.timestamp = timestamp;
        this.offset = offset;
        this.key = key;
        this.valueSize = valueSize;
    }

    long getTimestamp() {
        return timestamp;
    }

    long getOffset() {
        return offset;
    }

    String getKey() {
        return key;
    }

    long getValueSize() {
        return valueSize;
    }

    boolean isDeleted() {
        return valueSize == HintFileFormat.DELETED_VALUE_SIZE;
    }
}
