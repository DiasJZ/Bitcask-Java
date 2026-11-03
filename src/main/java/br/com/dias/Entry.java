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

    public static Entry decode(byte[] bytes) {
        int headerSize = Integer.BYTES + 3 * Long.BYTES;
        if (bytes.length < headerSize) {
            throw new IllegalArgumentException("Incomplete record header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        int storedCrc = buffer.getInt();

        CRC32 crc = new CRC32();
        crc.update(bytes, Integer.BYTES, bytes.length - Integer.BYTES);

        if (crc.getValue() != Integer.toUnsignedLong(storedCrc)) {
            throw new IllegalArgumentException("Invalid record checksum");
        }

        long timestamp = buffer.getLong();
        long keySize = buffer.getLong();
        long valueSize = buffer.getLong();

        int remaining = buffer.remaining();
        if (keySize < 0 || valueSize < 0 || keySize > remaining || valueSize != remaining - keySize) {
            throw new IllegalArgumentException("Invalid key or value size");
        }

        byte[] keyBytes = new byte[(int) keySize];
        buffer.get(keyBytes);

        byte[] valueBytes = new byte[(int) valueSize];
        buffer.get(valueBytes);

        String key = new String(keyBytes, StandardCharsets.UTF_8);
        String value = new String(valueBytes, StandardCharsets.UTF_8);

        return new Entry(timestamp, key, value);
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