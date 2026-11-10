package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintIndexLoaderTest {
    @TempDir
    Path directory;

    @Test
    void shouldReplayValidatedHintsIncludingUpdatesAndCrossFileDeletions() throws IOException {
        Path hint = writeSourceAndHint();
        byte[] hintBytes = Files.readAllBytes(hint);
        byte[] dataBytes = Files.readAllBytes(directory.resolve("7.cask"));
        Map<String, EntryLocation> index = previousIndex();

        assertTrue(HintIndexLoader.load(directory, 7, index));

        assertMatchesDataScan(index);
        assertFalse(index.containsKey("removed"));
        assertEquals(0L, index.get("kept").getFileId());
        assertEquals("Bia", CaskFile.readAt(directory, index.get("name")).getValue());
        assertEquals("café", CaskFile.readAt(directory, index.get("naïve")).getValue());
        assertEquals("", CaskFile.readAt(directory, index.get("empty")).getValue());
        assertArrayEquals(hintBytes, Files.readAllBytes(hint));
        assertArrayEquals(dataBytes, Files.readAllBytes(directory.resolve("7.cask")));
    }

    @Test
    void shouldScanSourceWhenHintIsMissingAndIgnoreTemporaryHints() throws IOException {
        Path hint = writeSourceAndHint();
        Path temporary = directory.resolve(".hint-7-unfinished.tmp");
        Files.move(hint, temporary);
        Map<String, EntryLocation> index = previousIndex();

        assertFalse(HintIndexLoader.load(directory, 7, index));

        assertMatchesDataScan(index);
        assertFalse(Files.exists(hint));
        assertTrue(Files.exists(temporary));
    }

    @Test
    void shouldDiscardCorruptHintEntriesAndScanSource() throws IOException {
        Path hint = writeSourceAndHint();
        byte[] corrupted = Files.readAllBytes(hint);
        corrupted[68] = (byte) 'x';
        Files.write(hint, corrupted);
        Map<String, EntryLocation> index = previousIndex();

        assertFalse(HintIndexLoader.load(directory, 7, index));

        assertFalse(index.containsKey("xame"));
        assertMatchesDataScan(index);
        assertArrayEquals(corrupted, Files.readAllBytes(hint));
    }

    @Test
    void shouldScanSourceForUnsupportedVersionsWrongIdsAndInvalidMetadata() throws IOException {
        Path hint = writeSourceAndHint();
        byte[] original = Files.readAllBytes(hint);
        for (int position : new int[]{0, 4, 8, 24, 44}) {
            byte[] changed = original.clone();
            changed[position] ^= 1;
            updateChecksum(changed);
            Files.write(hint, changed);
            Map<String, EntryLocation> index = previousIndex();

            assertFalse(HintIndexLoader.load(directory, 7, index));

            assertMatchesDataScan(index);
            assertArrayEquals(changed, Files.readAllBytes(hint));
        }
    }

    @Test
    void shouldScanSourceWhenHintIsTruncated() throws IOException {
        Path hint = writeSourceAndHint();
        byte[] original = Files.readAllBytes(hint);
        for (int length : new int[]{0, 12, original.length - 1}) {
            Files.write(hint, Arrays.copyOf(original, length));
            Map<String, EntryLocation> index = previousIndex();

            assertFalse(HintIndexLoader.load(directory, 7, index));

            assertMatchesDataScan(index);
        }
    }

    @Test
    void shouldDetectSameLengthSourceReplacementEvenWithTheSameModificationTime() throws IOException {
        Path source = directory.resolve("7.cask");
        Files.write(source, new Entry(1000L, "name", "Ana").encode());
        Path hint = HintFileWriter.write(directory, 7);
        byte[] originalHint = Files.readAllBytes(hint);
        var modified = Files.getLastModifiedTime(source);
        long oldSize = Files.size(source);
        Files.write(source, new Entry(1000L, "city", "Rio").encode());
        Files.setLastModifiedTime(source, modified);
        assertEquals(oldSize, Files.size(source));
        Map<String, EntryLocation> index = new HashMap<>();

        assertFalse(HintIndexLoader.load(directory, 7, index));

        assertEquals(1, index.size());
        assertFalse(index.containsKey("name"));
        assertEquals("Rio", CaskFile.readAt(directory, index.get("city")).getValue());
        assertArrayEquals(originalHint, Files.readAllBytes(hint));
    }

    @Test
    void shouldDetectAppendedRecordsAndReplayTheirDeletions() throws IOException {
        writeSourceAndHint();
        try (CaskFile source = new CaskFile(directory, 7)) {
            source.append(Entry.tombstone(1L, "name"));
            source.append(new Entry(1L, "new", "value"));
        }
        Map<String, EntryLocation> index = previousIndex();

        assertFalse(HintIndexLoader.load(directory, 7, index));

        assertMatchesDataScan(index);
        assertFalse(index.containsKey("name"));
        assertEquals("value", CaskFile.readAt(directory, index.get("new")).getValue());
    }

    @Test
    void shouldPreserveIndexWhenCorruptSourceFailsAfterValidRecords() throws IOException {
        Path hint = writeSourceAndHint();
        Path source = directory.resolve("7.cask");
        byte[] corrupted = Files.readAllBytes(source);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(source, corrupted);
        byte[] originalHint = Files.readAllBytes(hint);
        for (boolean keepHint : new boolean[]{true, false}) {
            if (!keepHint) {
                Files.delete(hint);
            }
            Map<String, EntryLocation> index = previousIndex();
            Map<String, EntryLocation> original = Map.copyOf(index);

            assertThrows(IllegalArgumentException.class, () -> HintIndexLoader.load(directory, 7, index));

            assertEquals(original, index);
            assertArrayEquals(corrupted, Files.readAllBytes(source));
            if (keepHint) {
                assertArrayEquals(originalHint, Files.readAllBytes(hint));
            }
        }
    }

    @Test
    void shouldPropagateTruncatedSourceErrorsWithoutApplyingPartialChanges() throws IOException {
        writeSourceAndHint();
        Path source = directory.resolve("7.cask");
        byte[] bytes = Files.readAllBytes(source);
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
        Files.write(source, truncated);
        Map<String, EntryLocation> index = previousIndex();
        Map<String, EntryLocation> original = Map.copyOf(index);

        assertThrows(IOException.class, () -> HintIndexLoader.load(directory, 7, index));

        assertEquals(original, index);
        assertArrayEquals(truncated, Files.readAllBytes(source));
    }

    @Test
    void shouldNotUseAHintWhenItsSourceIsMissing() throws IOException {
        Path hint = writeSourceAndHint();
        Files.delete(directory.resolve("7.cask"));
        Map<String, EntryLocation> index = previousIndex();
        Map<String, EntryLocation> original = Map.copyOf(index);

        assertThrows(IOException.class, () -> HintIndexLoader.load(directory, 7, index));

        assertEquals(original, index);
        assertFalse(Files.exists(directory.resolve("7.cask")));
        assertTrue(Files.exists(hint));
    }

    @Test
    void shouldLoadAnEmptyHintWithoutChangingEarlierEntries() throws IOException {
        Files.createFile(directory.resolve("7.cask"));
        HintFileWriter.write(directory, 7);
        Map<String, EntryLocation> index = previousIndex();
        Map<String, EntryLocation> original = Map.copyOf(index);

        assertTrue(HintIndexLoader.load(directory, 7, index));

        assertEquals(original, index);
    }

    @Test
    void shouldRejectNegativeFileIdsBeforeChangingTheIndex() {
        Map<String, EntryLocation> index = previousIndex();
        Map<String, EntryLocation> original = Map.copyOf(index);

        assertThrows(IllegalArgumentException.class, () -> HintIndexLoader.load(directory, -1, index));

        assertEquals(original, index);
    }

    private Path writeSourceAndHint() throws IOException {
        try (CaskFile source = new CaskFile(directory, 7)) {
            source.append(new Entry(9000L, "name", "Ana"));
            source.append(Entry.tombstone(1000L, "removed"));
            source.append(new Entry(2000L, "name", "Bia"));
            source.append(new Entry(3000L, "empty", ""));
            source.append(new Entry(4000L, "naïve", "café"));
        }
        return HintFileWriter.write(directory, 7);
    }

    private Map<String, EntryLocation> previousIndex() {
        Map<String, EntryLocation> index = new HashMap<>();
        index.put("name", new EntryLocation(0, 0));
        index.put("removed", new EntryLocation(0, 35));
        index.put("kept", new EntryLocation(0, 70));
        return index;
    }

    private void assertMatchesDataScan(Map<String, EntryLocation> actual) throws IOException {
        Map<String, EntryLocation> expected = previousIndex();
        try (CaskFile source = CaskFile.openReadOnly(directory, 7)) {
            source.loadIndex(7, expected);
        }
        assertEquals(expected.keySet(), actual.keySet());
        for (String key : expected.keySet()) {
            assertEquals(expected.get(key).getFileId(), actual.get(key).getFileId());
            assertEquals(expected.get(key).getOffset(), actual.get(key).getOffset());
        }
    }

    private static void updateChecksum(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - 4);
        ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) checksum.getValue());
    }
}
