package br.com.dias;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class EntryTest {

    @Test
    void shouldStoreProvidedValues() {
        Entry entry = new Entry(1000L, "nome", "Ana");

        assertEquals(1000L, entry.getTimestamp());
        assertEquals("nome", entry.getKey());
        assertEquals("Ana", entry.getValue());
    }

    @Test
    void shouldCalculateUtf8Sizes() {
        Entry entry = new Entry(1000L, "ação", "café");

        assertEquals(6L, entry.getKeySize());
        assertEquals(5L, entry.getValueSize());
    }

    @Test
    void shouldEncodePayloadInOrder() {
        Entry entry = new Entry(1000L, "nome", "Ana");

        byte[] payload = entry.encodePayload();

        assertEquals(31, payload.length);

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        assertEquals(1000L, buffer.getLong());
        assertEquals(4L, buffer.getLong());
        assertEquals(3L, buffer.getLong());

        byte[] keyBytes = new byte[4];
        buffer.get(keyBytes);

        byte[] valueBytes = new byte[3];
        buffer.get(valueBytes);

        assertArrayEquals("nome".getBytes(StandardCharsets.UTF_8), keyBytes);
        assertArrayEquals("Ana".getBytes(StandardCharsets.UTF_8), valueBytes);

        assertEquals(0, buffer.remaining());
    }
    @Test
    void shouldEncodeRecordWithValidChecksum() {
        Entry entry = new Entry(1000L, "nome", "Ana");

        byte[] record = entry.encode();

        assertEquals(35, record.length);

        ByteBuffer buffer = ByteBuffer.wrap(record);
        buffer.order(ByteOrder.BIG_ENDIAN);

        long storedChecksum = Integer.toUnsignedLong(buffer.getInt());

        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        CRC32 crc = new CRC32();
        crc.update(payload);

        assertEquals(crc.getValue(), storedChecksum);
        assertArrayEquals(entry.encodePayload(), payload);
    }
}