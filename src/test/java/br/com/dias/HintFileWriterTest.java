package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintFileWriterTest {
    @TempDir
    Path directory;

    @Test
    void shouldPreserveRecordOrderOffsetsUpdatesAndDeletions() throws IOException {
        Entry[] entries = {
                new Entry(3000L, "name", "Ana"),
                new Entry(1000L, "naïve", "café"),
                new Entry(2000L, "name", "Bia"),
                Entry.tombstone(4000L, "name"),
                new Entry(5000L, "", "")
        };
        try (CaskFile source = new CaskFile(directory, 7)) {
            for (Entry entry : entries) {
                source.append(entry);
            }
        }
        byte[] data = Files.readAllBytes(directory.resolve("7.cask"));

        Path hint = HintFileWriter.write(directory, 7);

        assertEquals(directory.resolve("7.hint"), hint);
        byte[] bytes = Files.readAllBytes(hint);
        ByteBuffer fields = ByteBuffer.wrap(bytes);
        assertEquals(0x42484E54, fields.getInt());
        assertEquals(1, fields.getInt());
        assertEquals(7L, fields.getLong());
        assertEquals(data.length, fields.getLong());
        assertEquals(checksum(data), Integer.toUnsignedLong(fields.getInt()));
        assertEquals(entries.length, fields.getLong());
        long offset = 0;
        for (Entry entry : entries) {
            assertEquals(entry.getTimestamp(), fields.getLong());
            assertEquals(offset, fields.getLong());
            byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
            assertEquals(key.length, fields.getLong());
            assertEquals(entry.getValueSize(), fields.getLong());
            byte[] storedKey = new byte[key.length];
            fields.get(storedKey);
            assertArrayEquals(key, storedKey);
            offset += entry.encode().length;
        }
        assertEquals(4, fields.remaining());
        assertEquals(checksum(Arrays.copyOf(bytes, bytes.length - 4)), Integer.toUnsignedLong(fields.getInt()));
        assertArrayEquals(data, Files.readAllBytes(directory.resolve("7.cask")));
        assertFiles("7.cask", "7.hint");
    }

    @Test
    void shouldWriteAnEmptyHintWithValidChecksums() throws IOException {
        Files.createFile(directory.resolve("0.cask"));

        byte[] bytes = Files.readAllBytes(HintFileWriter.write(directory, 0));

        assertEquals(40, bytes.length);
        ByteBuffer fields = ByteBuffer.wrap(bytes);
        fields.position(16);
        assertEquals(0L, fields.getLong());
        assertEquals(0, fields.getInt());
        assertEquals(0L, fields.getLong());
        assertEquals(checksum(Arrays.copyOf(bytes, 36)), Integer.toUnsignedLong(fields.getInt()));
    }

    @Test
    void shouldStreamLargeValuesWithoutCopyingThemIntoTheHint() throws IOException {
        byte[] data = new Entry(1000L, "note", "x".repeat(100_000)).encode();
        Files.write(directory.resolve("0.cask"), data);

        byte[] hint = Files.readAllBytes(HintFileWriter.write(directory, 0));

        assertEquals(76, hint.length);
        ByteBuffer fields = ByteBuffer.wrap(hint);
        assertEquals(checksum(data), Integer.toUnsignedLong(fields.getInt(24)));
        assertEquals(100_000L, fields.getLong(60));
        assertArrayEquals(data, Files.readAllBytes(directory.resolve("0.cask")));
    }

    @Test
    void shouldReplaceAnExistingHintOnlyWithCompletedOutput() throws IOException {
        Files.write(directory.resolve("0.cask"), new Entry(1000L, "name", "Ana").encode());
        Path hint = HintFileWriter.write(directory, 0);
        byte[] expected = Files.readAllBytes(hint);
        Files.writeString(hint, "old hint");

        HintFileWriter.write(directory, 0);

        assertArrayEquals(expected, Files.readAllBytes(hint));
        assertFiles("0.cask", "0.hint");
    }

    @Test
    void shouldKeepOldHintWhenSourceIsCorrupted() throws IOException {
        try (CaskFile source = new CaskFile(directory, 0)) {
            source.append(new Entry(1000L, "name", "Ana"));
            source.append(new Entry(2000L, "name", "Bia"));
        }
        Path hint = HintFileWriter.write(directory, 0);
        byte[] previous = Files.readAllBytes(hint);
        Path source = directory.resolve("0.cask");
        byte[] corrupted = Files.readAllBytes(source);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(source, corrupted);

        assertThrows(IOException.class, () -> HintFileWriter.write(directory, 0));

        assertArrayEquals(previous, Files.readAllBytes(hint));
        assertArrayEquals(corrupted, Files.readAllBytes(source));
        assertFiles("0.cask", "0.hint");
    }

    @Test
    void shouldRejectTruncatedHeadersAndPayloadsWithoutPublishingHints() throws IOException {
        byte[] record = new Entry(1000L, "name", "Ana").encode();
        for (int length : new int[]{3, 27, record.length - 1}) {
            byte[] truncated = Arrays.copyOf(record, length);
            Files.write(directory.resolve("0.cask"), truncated);

            assertThrows(IOException.class, () -> HintFileWriter.write(directory, 0));

            assertArrayEquals(truncated, Files.readAllBytes(directory.resolve("0.cask")));
            assertFiles("0.cask");
        }
    }

    @Test
    void shouldRejectInvalidLengthsBeforeAllocatingPayloads() throws IOException {
        long[][] sizes = {{-1, 0}, {0, -2}, {Long.MAX_VALUE, 0}, {0, Long.MAX_VALUE}};
        for (long[] size : sizes) {
            byte[] record = ByteBuffer.allocate(28).putInt(0).putLong(1000L)
                    .putLong(size[0]).putLong(size[1]).array();
            Files.write(directory.resolve("0.cask"), record);

            assertThrows(IOException.class, () -> HintFileWriter.write(directory, 0));

            assertFiles("0.cask");
        }
    }

    @Test
    void shouldRejectInvalidUtf8Keys() throws IOException {
        byte[] record = new Entry(1000L, "a", "value").encode();
        record[28] = (byte) 0xFF;
        ByteBuffer.wrap(record).putInt((int) checksum(Arrays.copyOfRange(record, 4, record.length)));
        Files.write(directory.resolve("0.cask"), record);

        assertThrows(IOException.class, () -> HintFileWriter.write(directory, 0));

        assertArrayEquals(record, Files.readAllBytes(directory.resolve("0.cask")));
        assertFiles("0.cask");
    }

    @Test
    void shouldNotCreateMissingSourceFiles() throws IOException {
        assertThrows(IOException.class, () -> HintFileWriter.write(directory, 0));
        assertThrows(IllegalArgumentException.class, () -> HintFileWriter.write(directory, -1));

        assertFiles();
    }

    @Test
    void shouldRemoveTemporaryOutputWhenPublicationFails() throws IOException {
        Files.write(directory.resolve("0.cask"), new Entry(1000L, "name", "Ana").encode());
        Path occupied = Files.createDirectory(directory.resolve("0.hint"));
        Files.writeString(occupied.resolve("keep.txt"), "keep");

        assertThrows(IOException.class, () -> HintFileWriter.write(directory, 0));

        assertTrue(Files.isDirectory(occupied));
        assertEquals("keep", Files.readString(occupied.resolve("keep.txt")));
        assertFiles("0.cask", "0.hint");
    }

    private static long checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private void assertFiles(String... expected) throws IOException {
        try (var paths = Files.list(directory)) {
            assertEquals(Set.of(expected), paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
    }
}
