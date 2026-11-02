package br.com.dias;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

public class Entry {

    private final long timestamp;
    private final String key;
    private final String value;

    public Entry(long timestamp, String key, String value) {
        this.timestamp = timestamp;
        this.key = key;
        this.value = value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public long getKeySize() {
        return key.getBytes(StandardCharsets.UTF_8).length;
    }

    public long getValueSize() {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public byte[] encodePayload() {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);

        int size = Math.toIntExact(24L + keyBytes.length + valueBytes.length);

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.putLong(timestamp);
        buffer.putLong(keyBytes.length);
        buffer.putLong(valueBytes.length);
        buffer.put(keyBytes);
        buffer.put(valueBytes);

        return buffer.array();
    }

    public byte[] encode() {
        byte[] payload = encodePayload();

        CRC32 crc = new CRC32();
        crc.update(payload);

        int size = Math.addExact(4, payload.length);

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.putInt((int) crc.getValue());
        buffer.put(payload);

        return buffer.array();
    }

}