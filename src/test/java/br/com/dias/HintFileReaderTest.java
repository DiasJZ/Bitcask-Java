package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintFileReaderTest {
    @TempDir
    Path directory;

    @Test
    void shouldReadWriterOutputWithoutLosingUpdatesOrDeletions() throws IOException {
        Entry[] records = {
                new Entry(3000L, "name", "Ana"),
                new Entry(1000L, "naïve", "café"),
                new Entry(2000L, "name", "Bia"),
                Entry.tombstone(4000L, "name"),
                new Entry(5000L, "", "")
        };
        try (CaskFile source = new CaskFile(directory, 7)) {
            for (Entry record : records) {
                source.append(record);
            }
        }
        byte[] data = Files.readAllBytes(directory.resolve("7.cask"));
        Path path = HintFileWriter.write(directory, 7);
        byte[] originalHint = Files.readAllBytes(path);

        HintFile hint = HintFileReader.read(directory, 7);

        assertEquals(7L, hint.getFileId());
        assertEquals(data.length, hint.getDataSize());
        CRC32 checksum = new CRC32();
        checksum.update(data);
        assertEquals(checksum.getValue(), hint.getDataChecksum());
        assertEquals(records.length, hint.getEntries().size());
        long offset = 0;
        for (int i = 0; i < records.length; i++) {
            Entry expected = records[i];
            HintEntry actual = hint.getEntries().get(i);
            assertEquals(expected.getTimestamp(), actual.getTimestamp());
            assertEquals(offset, actual.getOffset());
            assertEquals(expected.getKey(), actual.getKey());
            assertEquals(expected.getValueSize(), actual.getValueSize());
            assertEquals(expected.isDeleted(), actual.isDeleted());
            offset += expected.encode().length;
        }
        assertTrue(hint.getEntries().get(3).isDeleted());
        assertFalse(hint.getEntries().get(4).isDeleted());
        assertThrows(UnsupportedOperationException.class, () -> hint.getEntries().clear());
        assertArrayEquals(data, Files.readAllBytes(directory.resolve("7.cask")));
        assertArrayEquals(originalHint, Files.readAllBytes(path));
    }

    @Test
    void shouldReadAnIndependentFixtureWithoutOpeningTheSource() throws IOException {
        Files.write(directory.resolve("7.hint"), fixture());

        HintFile hint = HintFileReader.read(directory, 7);

        assertEquals(35L, hint.getDataSize());
        assertEquals(0xFEDCBA98L, hint.getDataChecksum());
        assertEquals(1, hint.getEntries().size());
        assertEquals("name", hint.getEntries().get(0).getKey());
        assertEquals(1000L, hint.getEntries().get(0).getTimestamp());
        assertEquals(3L, hint.getEntries().get(0).getValueSize());
        assertFalse(Files.exists(directory.resolve("7.cask")));
    }

    @Test
    void shouldReadAnEmptyHint() throws IOException {
        Files.createFile(directory.resolve("7.cask"));
        HintFileWriter.write(directory, 7);

        HintFile hint = HintFileReader.read(directory, 7);

        assertEquals(0L, hint.getDataSize());
        assertEquals(0L, hint.getDataChecksum());
        assertTrue(hint.getEntries().isEmpty());
    }

    @Test
    void shouldRejectUnsupportedHeadersAndMismatchedFileIds() throws IOException {
        for (int position : new int[]{0, 4, 8}) {
            byte[] bytes = fixture();
            bytes[position] ^= 1;
            rejectWithUpdatedChecksum(bytes);
        }
    }

    @Test
    void shouldRejectTruncationAndTrailingBytes() throws IOException {
        byte[] valid = fixture();
        for (int length : new int[]{0, 35, 39, 60, valid.length - 1, valid.length + 1}) {
            Files.write(directory.resolve("7.hint"), Arrays.copyOf(valid, length));

            assertThrows(IOException.class, () -> HintFileReader.read(directory, 7));
        }
    }

    @Test
    void shouldRejectDamagedContentsAndTrailer() throws IOException {
        for (int position : new int[]{36, 68, 75}) {
            byte[] bytes = fixture();
            bytes[position] ^= 1;
            Files.write(directory.resolve("7.hint"), bytes);

            IOException error = assertThrows(IOException.class, () -> HintFileReader.read(directory, 7));

            assertEquals("Invalid hint checksum", error.getMessage());
        }
    }

    @Test
    void shouldRejectInvalidCountsSizesAndOffsets() throws IOException {
        long[][] changes = {
                {16, -1}, {16, 34}, {16, 36},
                {28, -1}, {28, 0}, {28, 2}, {28, Long.MAX_VALUE},
                {44, -1}, {44, 1},
                {52, -1}, {52, Long.MAX_VALUE},
                {60, -2}, {60, Long.MAX_VALUE}
        };
        for (long[] change : changes) {
            byte[] bytes = fixture();
            ByteBuffer.wrap(bytes).putLong((int) change[0], change[1]);
            rejectWithUpdatedChecksum(bytes);
        }
    }

    @Test
    void shouldRejectRecordsLargerThanTheDataReaderSupports() throws IOException {
        byte[] bytes = fixture();
        long valueSize = Integer.MAX_VALUE;
        ByteBuffer.wrap(bytes).putLong(16, 28L + 4 + valueSize).putLong(60, valueSize);

        rejectWithUpdatedChecksum(bytes);
    }

    @Test
    void shouldRejectMalformedUtf8EvenWithAValidChecksum() throws IOException {
        byte[] bytes = fixture();
        bytes[68] = (byte) 0xFF;

        rejectWithUpdatedChecksum(bytes);
    }

    @Test
    void shouldRejectMissingHintsAndNegativeFileIdsWithoutCreatingFiles() throws IOException {
        assertThrows(IOException.class, () -> HintFileReader.read(directory, 7));
        assertThrows(IllegalArgumentException.class, () -> HintFileReader.read(directory, -1));

        try (var paths = Files.list(directory)) {
            assertEquals(0L, paths.count());
        }
    }

    private byte[] fixture() {
        byte[] bytes = ByteBuffer.allocate(76)
                .putInt(0x42484E54).putInt(1).putLong(7)
                .putLong(35).putInt(0xFEDCBA98).putLong(1)
                .putLong(1000).putLong(0).putLong(4).putLong(3)
                .put("name".getBytes(StandardCharsets.UTF_8)).putInt(0).array();
        updateChecksum(bytes);
        return bytes;
    }

    private void rejectWithUpdatedChecksum(byte[] bytes) throws IOException {
        updateChecksum(bytes);
        Path path = directory.resolve("7.hint");
        Files.write(path, bytes);

        assertThrows(IOException.class, () -> HintFileReader.read(directory, 7));

        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    private static void updateChecksum(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - 4);
        ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) checksum.getValue());
    }
}
