package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BitcaskCompactionTest {
    @TempDir
    Path directory;

    @Test
    void shouldCompactAndContinueReadingWritingAndDeleting() throws IOException {
        Files.writeString(directory.resolve("notes.txt"), "keep this file");
        try (Bitcask store = new Bitcask(directory, true, 40)) {
            store.put("name", "Ana");
            store.put("drink", "coffee");
            store.put("name", "Bia");
            store.delete("drink");
            store.put("note", "");
            store.put("naïve", "café");
            store.compact();

            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("drink").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
            assertEquals("café", store.get("naïve").orElseThrow());
            assertTrue(Files.exists(directory.resolve("CURRENT")));
            assertFalse(Files.exists(directory.resolve("0.cask")));
            CompactionState state = CompactionRecovery.recover(directory);
            Path base = state.getDataDirectory().resolve("0.cask");
            byte[] before = Files.readAllBytes(base);

            store.put("name", "Clara");
            store.delete("note");
            store.put("drink", "tea");
            assertArrayEquals(before, Files.readAllBytes(base));
            assertEquals(state.getDataDirectory(), CompactionRecovery.recover(directory).getDataDirectory());
        }

        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Clara", store.get("name").orElseThrow());
            assertEquals("tea", store.get("drink").orElseThrow());
            assertTrue(store.get("note").isEmpty());
            assertEquals("café", store.get("naïve").orElseThrow());
        }
        assertEquals("keep this file", Files.readString(directory.resolve("notes.txt")));
    }

    @Test
    void shouldRemoveOldCompactionFiles() throws IOException {
        Path first;
        Path second;
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            store.put("name", "Bia");
            store.compact();
            first = CompactionRecovery.recover(directory).getDataDirectory();
            store.put("name", "Clara");
            store.compact();
            second = CompactionRecovery.recover(directory).getDataDirectory();
            assertFalse(Files.exists(first));
            assertEquals("Clara", store.get("name").orElseThrow());
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.compact();
            assertFalse(Files.exists(second));
            assertEquals("Clara", store.get("name").orElseThrow());
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Clara", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldCompactAnEmptyDatabase() throws IOException {
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.compact();
            store.put("name", "Ana");
            store.delete("name");
            store.compact();
            assertTrue(store.get("name").isEmpty());
            Path selected = CompactionRecovery.recover(directory).getDataDirectory();
            assertEquals(0L, Files.size(selected.resolve("0.cask")));
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertTrue(store.get("name").isEmpty());
            store.put("name", "");
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldKeepDatabaseUsableWhenOutputGenerationFails() throws IOException {
        String largeValue = "a".repeat(100);
        try (Bitcask store = new Bitcask(directory, false, 200)) {
            store.put("large", largeValue);
        }
        byte[] before = Files.readAllBytes(directory.resolve("0.cask"));
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertThrows(IOException.class, store::compact);
            assertFalse(Files.exists(directory.resolve("CURRENT")));
            assertEquals(largeValue, store.get("large").orElseThrow());
            assertArrayEquals(before, Files.readAllBytes(directory.resolve("0.cask")));
            store.put("name", "Ana");
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals(largeValue, store.get("large").orElseThrow());
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldReopenAfterCommitBeforeCleanupOrWriterCreation() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(new Entry(1000L, "obsolete", "old"));
            file.append(Entry.tombstone(2000L, "obsolete"));
            index = file.loadIndex(0);
        }
        CompactionPlan plan = new CompactionPlan(directory, index);
        Path output = CompactionWriter.write(directory, plan, 40);
        CompactionPreparation.prepare(directory, output, plan, 40);
        CompactionRecovery.commit(directory, output);
        byte[] base = Files.readAllBytes(output.resolve("0.cask"));

        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            assertTrue(store.get("obsolete").isEmpty());
            store.put("name", "Bia");
            assertArrayEquals(base, Files.readAllBytes(output.resolve("0.cask")));
            assertTrue(Files.size(output.resolve("1.cask")) > 0);
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("obsolete").isEmpty());
        }
    }

    @Test
    void shouldKeepWriterLockDuringAndAfterCompaction() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("name", "Ana");
            store.compact();
            assertThrows(IOException.class, () -> {
                try (Bitcask ignored = new Bitcask(directory)) {
                    fail("A second writer should not open the database");
                }
            });
            assertEquals("Ana", store.get("name").orElseThrow());
        }
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldRejectCompactionAfterClose() throws IOException {
        Bitcask store = new Bitcask(directory);
        store.close();
        assertThrows(IOException.class, store::compact);
        assertFalse(Files.exists(directory.resolve("CURRENT")));
    }
}
