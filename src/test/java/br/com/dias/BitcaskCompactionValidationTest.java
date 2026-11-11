package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BitcaskCompactionValidationTest {
    @TempDir
    Path directory;

    @Test
    void shouldReduceStoredBytesAndKeepOnlyCurrentValues() throws IOException {
        String currentValue = "";
        long before;
        long after;
        try (Bitcask store = new Bitcask(directory, false, 256)) {
            for (int i = 0; i < 80; i++) {
                currentValue = "version-" + i + "-" + "x".repeat(60);
                store.put("name", currentValue);
                store.put("obsolete", "old-" + i);
            }
            store.delete("obsolete");
            store.put("note", "");
            before = storedBytes(directory);
            store.compact();
            after = storedBytes(directory);

            assertTrue(after < before / 2, "Compaction should remove most obsolete data");
            assertEquals(currentValue, store.get("name").orElseThrow());
            assertTrue(store.get("obsolete").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
        }
        try (Bitcask store = new Bitcask(directory, false, 256)) {
            assertEquals(currentValue, store.get("name").orElseThrow());
            assertTrue(store.get("obsolete").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
        }
    }

    @Test
    void shouldMatchMapAfterCompaction() throws IOException {
        String[] keys = {"name", "city", "drink", "note", "naïve", ""};
        String[] values = {"Ana", "Recife", "café", "", "résumé", "blue"};
        for (boolean syncOnPut : new boolean[]{false, true}) {
            Path database = directory.resolve("sync-" + syncOnPut);
            Map<String, String> expected = new HashMap<>();
            Random random = new Random(71);
            for (int round = 0; round < 3; round++) {
                try (Bitcask store = new Bitcask(database, syncOnPut, 80)) {
                    assertValues(store, expected, keys);
                    for (int operation = 0; operation < 30; operation++) {
                        String key = keys[random.nextInt(keys.length)];
                        if (random.nextInt(3) == 0) {
                            store.delete(key);
                            expected.remove(key);
                        } else {
                            String value = values[random.nextInt(values.length)];
                            store.put(key, value);
                            expected.put(key, value);
                        }
                        if (operation % 10 == 9) {
                            store.compact();
                        }
                        assertValues(store, expected, keys);
                    }
                }
            }
            try (Bitcask store = new Bitcask(database, syncOnPut, 80)) {
                assertValues(store, expected, keys);
            }
        }
    }

    @Test
    void shouldRecoverAfterAnotherJvmHaltsAtCompactionBoundaries() throws Exception {
        for (String phase : new String[]{"writing", "ready", "committed"}) {
            Path database = directory.resolve(phase);
            try (Bitcask store = new Bitcask(database, true, 2000)) {
                store.put("name", "Ana");
                store.put("obsolete", "old");
                store.put("name", "Bia");
                store.delete("obsolete");
                store.put("note", "");
            }

            assertEquals(23, runCrashProcess(database, phase));
            assertEquals(phase.equals("committed"), Files.exists(database.resolve("CURRENT")));

            try (Bitcask store = new Bitcask(database, false, 40)) {
                assertEquals("Bia", store.get("name").orElseThrow());
                assertTrue(store.get("obsolete").isEmpty());
                assertEquals("", store.get("note").orElseThrow());
                store.put("name", "Clara");
                store.compact();
            }
            try (Bitcask store = new Bitcask(database, false, 40)) {
                assertEquals("Clara", store.get("name").orElseThrow());
                assertTrue(store.get("obsolete").isEmpty());
                assertEquals("", store.get("note").orElseThrow());
            }
        }
    }

    @Test
    void shouldReleaseLockAfterRecoveryFailure() throws IOException {
        Path current = directory.resolve("CURRENT");
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            Files.createDirectory(current);
            assertThrows(IOException.class, store::compact);
            assertThrows(IOException.class, () -> store.get("name"));
            assertThrows(IOException.class, () -> store.put("name", "Bia"));
            Files.delete(current);

            try (Bitcask reopened = new Bitcask(directory, false, 40)) {
                assertEquals("Ana", reopened.get("name").orElseThrow());
                reopened.put("note", "");
            }
        }
    }

    @Test
    void shouldRejectCorruptedCompactedFiles() throws IOException {
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Bia");
            store.compact();
        }
        Path selected = CompactionRecovery.recover(directory).getDataDirectory();
        Path base = selected.resolve("0.cask");
        byte[] valid = Files.readAllBytes(base);
        byte[] corrupted = valid.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(base, corrupted);
        Files.write(directory.resolve("0.cask"), new Entry(1000L, "name", "Ana").encode());

        assertThrows(IOException.class, () -> {
            try (Bitcask ignored = new Bitcask(directory)) {
                fail("Corrupted committed data should prevent opening");
            }
        });

        Files.write(base, valid);
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldPreserveUnrelatedFilesInsideAnOldGeneration() throws IOException {
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            store.compact();
            Path previous = CompactionRecovery.recover(directory).getDataDirectory();
            Files.writeString(previous.resolve("notes.txt"), "keep this file");
            store.put("name", "Bia");
            store.compact();

            assertEquals("keep this file", Files.readString(previous.resolve("notes.txt")));
            assertFalse(Files.exists(previous.resolve("READY")));
            try (var files = Files.newDirectoryStream(previous, "*.cask")) {
                assertFalse(files.iterator().hasNext());
            }
            assertEquals("Bia", store.get("name").orElseThrow());
        }
    }

    private long storedBytes(Path root) throws IOException {
        long total = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total += Files.size(path);
            }
        }
        return total;
    }

    private void assertValues(Bitcask store, Map<String, String> expected, String[] keys) throws IOException {
        for (String key : keys) {
            assertEquals(Optional.ofNullable(expected.get(key)), store.get(key), "Unexpected value for key: " + key);
        }
    }

    private int runCrashProcess(Path database, String phase) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath,
                CompactionCrashProcess.class.getName(), database.toString(), phase)
                .redirectErrorStream(true)
                .redirectOutput(database.resolve("crash-process.log").toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Crash process timed out: " + phase);
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
