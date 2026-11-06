package br.com.dias;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EntryTest {

    @Test
    void shouldEncodeAndDecodeDeletionRecord() {
        byte[] record = Entry.tombstone(1000L, "naïve").encode();
        Entry decoded = Entry.decode(record);

        assertEquals(34, record.length);
        assertEquals(1000L, decoded.getTimestamp());
        assertEquals("naïve", decoded.getKey());
        assertEquals(-1L, decoded.getValueSize());
        assertTrue(decoded.isDeleted());
    }

    @Test
    void shouldRejectValueSizeBelowDeletionMarker() {
        byte[] record = Entry.tombstone(1000L, "name").encode();
        ByteBuffer buffer = ByteBuffer.wrap(record);
        buffer.putLong(Integer.BYTES + 2 * Long.BYTES, -2L);

        CRC32 crc = new CRC32();
        crc.update(record, Integer.BYTES, record.length - Integer.BYTES);
        buffer.putInt(0, (int) crc.getValue());

        assertThrows(IllegalArgumentException.class, () -> Entry.decode(record));
    }

    @Test
    void shouldStoreProvidedValues() {
        Entry entry = new Entry(1000L, "name", "Ana");

        assertEquals(1000L, entry.getTimestamp());
        assertEquals("name", entry.getKey());
        assertEquals("Ana", entry.getValue());
    }

    @Test
    void shouldCalculateUtf8Sizes() {
        Entry entry = new Entry(1000L, "naïve", "café");

        assertEquals(6L, entry.getKeySize());
        assertEquals(5L, entry.getValueSize());
    }

    @Test
    void shouldEncodePayloadInOrder() {
        Entry entry = new Entry(1000L, "name", "Ana");

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

        assertArrayEquals("name".getBytes(StandardCharsets.UTF_8), keyBytes);
        assertArrayEquals("Ana".getBytes(StandardCharsets.UTF_8), valueBytes);

        assertEquals(0, buffer.remaining());
    }
    @Test
    void shouldEncodeRecordWithValidChecksum() {
        Entry entry = new Entry(1000L, "name", "Ana");

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

    @Test
    void shouldRejectCorruptedRecord() {
        Entry entry = new Entry(1000L, "name", "Ana");
        byte[] record = entry.encode();

        record[record.length - 1] = (byte) 'o';

        assertThrows(IllegalArgumentException.class, () -> Entry.decode(record));
    }

    @Test
    void shouldRejectIncompleteHeader() {
        byte[] record = new byte[3];

        assertThrows(IllegalArgumentException.class, () -> Entry.decode(record));
    }

    @Test
    void shouldRejectInvalidValueSize() {
        byte[] record = new Entry(1000L, "name", "Ana").encode();
        ByteBuffer buffer = ByteBuffer.wrap(record);

        int valueSizeOffset = Integer.BYTES + 2 * Long.BYTES;
        buffer.putLong(valueSizeOffset, 4L);

        CRC32 crc = new CRC32();
        crc.update(record, Integer.BYTES, record.length - Integer.BYTES);
        buffer.putInt(0, (int) crc.getValue());

        assertThrows(IllegalArgumentException.class, () -> Entry.decode(record));
    }
}
