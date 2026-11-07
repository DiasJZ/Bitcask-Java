package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompactionWriterTest {
    @TempDir
    Path directory;

    @Test
    void shouldWriteOnlyCurrentRecordsAndKeepSourceFilesIntact() throws IOException {
        Map<String, EntryLocation> index = new HashMap<>();
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(new Entry(1000L, "drink", "coffee"));
            file.append(new Entry(1000L, "naïve", "café"));
            file.loadIndex(0, index);
        }
        try (CaskFile file = new CaskFile(directory, 1)) {
            file.append(new Entry(2000L, "name", "Bia"));
            file.append(Entry.tombstone(2000L, "drink"));
            file.append(new Entry(2000L, "note", ""));
            file.loadIndex(1, index);
        }
        byte[] first = Files.readAllBytes(directory.resolve("0.cask"));
        byte[] second = Files.readAllBytes(directory.resolve("1.cask"));
        CompactionPlan plan = new CompactionPlan(directory, index);

        Path output = CompactionWriter.write(directory, plan, 200);

        assertEquals(directory, output.getParent());
        assertTrue(output.getFileName().toString().startsWith(".compaction-"));
        Map<String, EntryLocation> compacted;
        try (CaskFile file = CaskFile.openReadOnly(output, 0)) {
            compacted = file.loadIndex(0);
        }
        assertEquals(index.keySet(), compacted.keySet());
        long expectedSize = 0;
        for (String key : plan.getKeys()) {
            Entry original = plan.readEntry(key);
            Entry copied = CaskFile.readAt(output, compacted.get(key));
            assertArrayEquals(original.encode(), copied.encode());
            expectedSize += original.encode().length;
        }
        assertEquals(expectedSize, Files.size(output.resolve("0.cask")));
        assertTrue(expectedSize < first.length + second.length);
        assertFalse(Files.exists(output.resolve("1.cask")));
        assertArrayEquals(first, Files.readAllBytes(directory.resolve("0.cask")));
        assertArrayEquals(second, Files.readAllBytes(directory.resolve("1.cask")));

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("drink").isEmpty());
            assertEquals("café", store.get("naïve").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
        }
    }

    @Test
    void shouldRotateTemporaryFilesAtTheByteLimit() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "naïve", "café"));
            file.append(new Entry(1000L, "other!", "café"));
            file.append(new Entry(1000L, "z", "x"));
            index = file.loadIndex(0);
        }
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        CompactionPlan plan = new CompactionPlan(directory, index);
        Path output = CompactionWriter.write(directory, plan, 78);

        assertEquals(78L, Files.size(output.resolve("0.cask")));
        assertEquals(30L, Files.size(output.resolve("1.cask")));
        assertFalse(Files.exists(output.resolve("2.cask")));
        try (Bitcask store = new Bitcask(output, false, 78)) {
            assertEquals("café", store.get("naïve").orElseThrow());
            assertEquals("café", store.get("other!").orElseThrow());
            assertEquals("x", store.get("z").orElseThrow());
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
    }

    @Test
    void shouldCreateAnEmptyDataFileWhenNoLiveRecordsRemain() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(Entry.tombstone(2000L, "name"));
            index = file.loadIndex(0);
        }
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        Path output = CompactionWriter.write(directory, new CompactionPlan(directory, index), 40);

        assertEquals(0L, Files.size(output.resolve("0.cask")));
        assertFalse(Files.exists(output.resolve("1.cask")));
        try (Bitcask store = new Bitcask(output)) {
            assertTrue(store.get("name").isEmpty());
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
    }

    @Test
    void shouldCleanPartialOutputWhenARecordExceedsTheLimit() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "a", "small"));
            file.append(new Entry(1000L, "z", "x".repeat(100)));
            index = file.loadIndex(0);
        }
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        Path existing = Files.createDirectory(directory.resolve(".compaction-existing"));
        Files.writeString(existing.resolve("keep.txt"), "keep this file");
        CompactionPlan plan = new CompactionPlan(directory, index);

        assertThrows(IOException.class, () -> CompactionWriter.write(directory, plan, 40));

        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
        assertEquals("keep this file", Files.readString(existing.resolve("keep.txt")));
        try (var paths = Files.list(directory)) {
            assertEquals(2L, paths.count());
        }
    }

    @Test
    void shouldCleanPartialOutputWhenASelectedRecordIsCorrupted() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "a", "first"));
            file.append(new Entry(1000L, "z", "last"));
            index = file.loadIndex(0);
        }
        Path source = directory.resolve("0.cask");
        byte[] corrupted = Files.readAllBytes(source);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(source, corrupted);
        CompactionPlan plan = new CompactionPlan(directory, index);

        assertThrows(IllegalArgumentException.class, () -> CompactionWriter.write(directory, plan, 40));

        assertArrayEquals(corrupted, Files.readAllBytes(source));
        try (var paths = Files.list(directory)) {
            assertEquals(1L, paths.count());
        }
    }
}
