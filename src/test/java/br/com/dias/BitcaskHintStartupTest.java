package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BitcaskHintStartupTest {
    @TempDir
    Path directory;

    @Test
    void shouldReplayMixedFilesInNumericOrderAndPreserveDeletions() throws IOException {
        writeFile(2, new Entry(9000L, "name", "Ana"), new Entry(9000L, "obsolete", "old"));
        writeFile(10, new Entry(1000L, "name", "Bia"), Entry.tombstone(1000L, "obsolete"));
        writeFile(11, new Entry(2000L, "naïve", "café"), new Entry(2000L, "temporary", "old"));
        writeFile(12, Entry.tombstone(1L, "temporary"), new Entry(1L, "note", ""));
        HintFileWriter.write(directory, 2);
        HintFileWriter.write(directory, 10);
        Path badHint = directory.resolve("11.hint");
        Files.writeString(badHint, "incomplete hint");
        byte[] original = Files.readAllBytes(directory.resolve("2.cask"));
        byte[] originalHint = Files.readAllBytes(directory.resolve("10.hint"));

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertEquals("café", store.get("naïve").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
            assertTrue(store.get("obsolete").isEmpty());
            assertTrue(store.get("temporary").isEmpty());
            store.put("city", "Recife");
        }
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Recife", store.get("city").orElseThrow());
            assertTrue(store.get("obsolete").isEmpty());
            assertTrue(store.get("temporary").isEmpty());
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("2.cask")));
        assertArrayEquals(originalHint, Files.readAllBytes(directory.resolve("10.hint")));
        assertEquals("incomplete hint", Files.readString(badHint));
        assertFalse(Files.exists(directory.resolve("12.hint")));
    }

    @Test
    void shouldIgnoreActiveFileHints() throws IOException {
        writeFile(0, new Entry(1000L, "name", "Ana"));
        Path hint = HintFileWriter.write(directory, 0);
        byte[] changed = Files.readAllBytes(hint);
        System.arraycopy("fake".getBytes(StandardCharsets.UTF_8), 0, changed, 68, 4);
        updateChecksum(changed);
        Files.write(hint, changed);
        HintFile parsed = HintFileReader.read(directory, 0);
        HintFileValidator.validate(directory, parsed);
        assertEquals("fake", parsed.getEntries().get(0).getKey());

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            assertTrue(store.get("fake").isEmpty());
            store.put("name", "Bia");
        }
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("fake").isEmpty());
        }
        assertArrayEquals(changed, Files.readAllBytes(hint));
    }

    @Test
    void shouldFallBackForMissingUnsupportedAndCorruptSealedHints() throws IOException {
        writeFile(0, new Entry(1000L, "name", "Ana"));
        writeFile(1, new Entry(2000L, "note", ""));
        Path hint = HintFileWriter.write(directory, 0);
        byte[] original = Files.readAllBytes(hint);
        for (String condition : new String[]{"missing", "unsupported", "corrupt"}) {
            if (condition.equals("missing")) {
                Files.delete(hint);
            } else {
                byte[] changed = original.clone();
                if (condition.equals("unsupported")) {
                    ByteBuffer.wrap(changed).putInt(4, 99);
                    updateChecksum(changed);
                } else {
                    changed[changed.length - 1] ^= 1;
                }
                Files.write(hint, changed);
            }

            try (Bitcask store = new Bitcask(directory)) {
                assertEquals("Ana", store.get("name").orElseThrow());
                assertEquals("", store.get("note").orElseThrow());
            }
            if (condition.equals("missing")) {
                assertFalse(Files.exists(hint));
            }
        }
    }

    @Test
    void shouldDetectSameSizeReplacementOfASealedSource() throws IOException {
        writeFile(0, new Entry(1000L, "name", "Ana"));
        writeFile(1, new Entry(2000L, "note", ""));
        Path hint = HintFileWriter.write(directory, 0);
        byte[] originalHint = Files.readAllBytes(hint);
        Path source = directory.resolve("0.cask");
        long originalSize = Files.size(source);
        Files.write(source, new Entry(1000L, "city", "Rio").encode());
        assertEquals(originalSize, Files.size(source));

        try (Bitcask store = new Bitcask(directory)) {
            assertTrue(store.get("name").isEmpty());
            assertEquals("Rio", store.get("city").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
        }
        assertArrayEquals(originalHint, Files.readAllBytes(hint));
    }

    @Test
    void shouldReleaseLockWhenSourceIsCorrupted() throws IOException {
        writeFile(0, new Entry(1000L, "name", "Ana"));
        writeFile(1, new Entry(2000L, "note", ""));
        HintFileWriter.write(directory, 0);
        Path source = directory.resolve("0.cask");
        byte[] original = Files.readAllBytes(source);
        byte[] corrupted = original.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(source, corrupted);
        byte[] active = Files.readAllBytes(directory.resolve("1.cask"));

        assertThrows(IllegalArgumentException.class, () -> {
            try (Bitcask ignored = new Bitcask(directory)) {
                fail("Corrupted source data must prevent opening");
            }
        });

        assertArrayEquals(corrupted, Files.readAllBytes(source));
        assertArrayEquals(active, Files.readAllBytes(directory.resolve("1.cask")));
        Files.write(source, original);
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            store.put("city", "Recife");
        }
    }

    @Test
    void shouldOpenBaseWithoutAWritableFile() throws IOException {
        writeFile(0, new Entry(1000L, "name", "Ana"), new Entry(2000L, "note", ""));
        Map<String, EntryLocation> index;
        try (CaskFile source = CaskFile.openReadOnly(directory, 0)) {
            index = source.loadIndex(0);
        }
        CompactionPlan plan = new CompactionPlan(directory, index);
        Path output = CompactionWriter.write(directory, plan, 40);
        CompactionPreparation.prepare(directory, output, plan, 40);
        CompactionRecovery.commit(directory, output);
        assertFalse(Files.exists(output.resolve("2.cask")));
        byte[] first = Files.readAllBytes(output.resolve("0.cask"));
        byte[] second = Files.readAllBytes(output.resolve("1.cask"));

        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
            store.delete("name");
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertTrue(store.get("name").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
            store.put("city", "Rio");
        }
        assertArrayEquals(first, Files.readAllBytes(output.resolve("0.cask")));
        assertArrayEquals(second, Files.readAllBytes(output.resolve("1.cask")));
        assertFalse(Files.exists(output.resolve("3.hint")));
    }

    @Test
    void shouldReopenOlderDatabasesWithoutGeneratingHints() throws IOException {
        writeFile(0, new Entry(1000L, "name", "Ana"), new Entry(1000L, "obsolete", "old"));
        writeFile(1, Entry.tombstone(2000L, "obsolete"), new Entry(2000L, "name", "Bia"));
        writeFile(2, new Entry(3000L, "note", ""));

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("obsolete").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
        }
        try (var paths = Files.newDirectoryStream(directory, "*.hint")) {
            assertFalse(paths.iterator().hasNext());
        }
    }

    private void writeFile(long fileId, Entry... entries) throws IOException {
        try (CaskFile file = new CaskFile(directory, fileId)) {
            for (Entry entry : entries) {
                file.append(entry);
            }
        }
    }

    private static void updateChecksum(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - 4);
        ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) checksum.getValue());
    }
}
