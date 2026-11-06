package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BitcaskRotationTest {
    @TempDir
    Path directory;

    @Test
    void shouldMatchReferenceMapAcrossRotationsAndRestarts() throws IOException {
        String[] keys = {"name", "city", "drink", "note", "color", "size", "count", "naïve", ""};
        String[] values = {"Ana", "Recife", "café", "", "blue", "résumé", "value-123"};

        for (boolean syncOnPut : new boolean[]{false, true}) {
            Path database = directory.resolve("sync-" + syncOnPut);
            Map<String, String> expected = new HashMap<>();
            Random random = new Random(42);
            for (int round = 0; round < 5; round++) {
                try (Bitcask store = new Bitcask(database, syncOnPut, 100)) {
                    assertValues(store, expected, keys);
                    for (int operation = 0; operation < 32; operation++) {
                        String key = keys[random.nextInt(keys.length)];
                        if (random.nextInt(4) == 0) {
                            store.delete(key);
                            expected.remove(key);
                        } else {
                            String value = values[random.nextInt(values.length)];
                            store.put(key, value);
                            expected.put(key, value);
                        }
                        assertValues(store, expected, keys);
                    }
                    store.sync();
                }
            }
            try (Bitcask store = new Bitcask(database, syncOnPut, 100)) {
                assertValues(store, expected, keys);
            }
            int fileCount = 0;
            try (var files = Files.newDirectoryStream(database, "*.cask")) {
                for (Path file : files) {
                    assertTrue(Files.size(file) <= 100, file.toString());
                    fileCount++;
                }
            }
            assertTrue(fileCount > 10);
        }
    }

    @Test
    void shouldRotateAtTheUtf8ByteLimitAndPreserveOtherKeys() throws IOException {
        byte[] original;
        try (Bitcask store = new Bitcask(directory, false, 78)) {
            store.put("naïve", "café");
            store.put("other!", "café");
            assertEquals(78L, Files.size(directory.resolve("0.cask")));
            assertFalse(Files.exists(directory.resolve("1.cask")));
            original = Files.readAllBytes(directory.resolve("0.cask"));

            store.delete("naïve");
            assertEquals(34L, Files.size(directory.resolve("1.cask")));
            assertTrue(store.get("naïve").isEmpty());
            assertEquals("café", store.get("other!").orElseThrow());
        }
        try (Bitcask store = new Bitcask(directory, false, 78)) {
            assertTrue(store.get("naïve").isEmpty());
            assertEquals("café", store.get("other!").orElseThrow());
            store.put("naïve", "");
        }
        try (Bitcask store = new Bitcask(directory, false, 78)) {
            assertEquals("", store.get("naïve").orElseThrow());
            assertEquals("café", store.get("other!").orElseThrow());
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
    }

    @Test
    void shouldPreserveExistingValueAfterRejectingAnOversizedUpdate() throws IOException {
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
            assertThrows(IOException.class, () -> store.put("name", "a".repeat(100)));
            assertThrows(IOException.class, () -> store.put("missing", "a".repeat(100)));
            assertEquals("Ana", store.get("name").orElseThrow());
            assertTrue(store.get("missing").isEmpty());
            assertFalse(Files.exists(directory.resolve("1.cask")));
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
            store.put("note", "");
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
            assertTrue(store.get("missing").isEmpty());
        }
    }

    @Test
    void shouldKeepOldDataWhenReopenedWithASmallerFileLimit() throws IOException {
        String largeValue = "a".repeat(100);
        try (Bitcask store = new Bitcask(directory, false, 200)) {
            store.put("large", largeValue);
            store.put("name", "Ana");
        }
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals(largeValue, store.get("large").orElseThrow());
            store.delete("name");
            store.put("note", "");
            assertTrue(Files.exists(directory.resolve("2.cask")));
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals(largeValue, store.get("large").orElseThrow());
            assertTrue(store.get("name").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
    }

    @Test
    void shouldPreserveIndexAndAllowRetryAfterRotationFails() throws IOException {
        Path blockedPath = directory.resolve("1.cask");
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            Files.createDirectory(blockedPath);
            assertThrows(IOException.class, () -> store.put("name", "Bia"));
            assertThrows(IOException.class, () -> store.delete("name"));
            assertEquals("Ana", store.get("name").orElseThrow());

            Files.delete(blockedPath);
            store.put("name", "Bia");
            assertEquals("Bia", store.get("name").orElseThrow());
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Bia", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldRejectTruncatedLastFileWithoutChangingEarlierFiles() throws IOException {
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            store.put("drink", "coffee");
        }
        byte[] first = Files.readAllBytes(directory.resolve("0.cask"));
        Path lastPath = directory.resolve("1.cask");
        byte[] complete = Files.readAllBytes(lastPath);
        byte[] truncated = Arrays.copyOf(complete, complete.length - 1);
        Files.write(lastPath, truncated);

        assertThrows(IOException.class, () -> new Bitcask(directory, false, 40));
        assertArrayEquals(first, Files.readAllBytes(directory.resolve("0.cask")));
        assertArrayEquals(truncated, Files.readAllBytes(lastPath));

        Files.write(lastPath, complete);
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            assertEquals("coffee", store.get("drink").orElseThrow());
        }
    }

    @Test
    void shouldReportAMissingOldFileWithoutRecreatingIt() throws IOException {
        Path oldPath = directory.resolve("0.cask");
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            store.put("drink", "coffee");
            Files.delete(oldPath);

            assertThrows(IOException.class, () -> store.get("name"));
            assertFalse(Files.exists(oldPath));
            assertEquals("coffee", store.get("drink").orElseThrow());
        }
    }

    private void assertValues(Bitcask store, Map<String, String> expected, String[] keys) throws IOException {
        for (String key : keys) {
            assertEquals(Optional.ofNullable(expected.get(key)), store.get(key), "Unexpected value for key: " + key);
        }
    }
}
