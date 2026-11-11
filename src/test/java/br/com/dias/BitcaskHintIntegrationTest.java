package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BitcaskHintIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void shouldMatchReferenceValuesWithMixedHintsAndWithNoHints() throws IOException {
        String[] keys = {"name", "city", "drink", "note", "naïve", "", "rocket-🚀", "removed"};
        String[] values = {"Ana", "Recife", "café", "", "résumé", "🚀", "blue"};
        for (boolean syncOnPut : new boolean[]{false, true}) {
            Path database = directory.resolve("sync-" + syncOnPut);
            Map<String, String> expected = new HashMap<>();
            Random random = new Random(91);
            for (int round = 0; round < 3; round++) {
                try (Bitcask store = new Bitcask(database, syncOnPut, 80)) {
                    assertValues(store, expected, keys);
                    for (int operation = 0; operation < 24; operation++) {
                        String key = keys[random.nextInt(keys.length)];
                        if (random.nextInt(3) == 0) {
                            store.delete(key);
                            expected.remove(key);
                        } else {
                            String value = values[random.nextInt(values.length)];
                            store.put(key, value);
                            expected.put(key, value);
                        }
                        assertValues(store, expected, keys);
                    }
                    for (String key : keys) {
                        String value = "current-" + round + "-" + "x".repeat(16);
                        store.put(key, value);
                        expected.put(key, value);
                    }
                    store.delete("removed");
                    expected.remove("removed");
                    store.compact();
                    store.put("name", "Ana");
                    expected.put("name", "Ana");
                    store.delete("city");
                    expected.remove("city");
                    store.put("note", "");
                    expected.put("note", "");
                    assertValues(store, expected, keys);
                }

                Path selected = CompactionRecovery.recover(database).getDataDirectory();
                Map<Path, byte[]> originalData = dataSnapshot(selected);
                List<Path> hints = hintFiles(selected);
                assertTrue(hints.size() >= 3, "The test requires valid, missing, and damaged hints");
                for (int i = 0; i < hints.size(); i++) {
                    if (i % 3 == 1) {
                        Files.delete(hints.get(i));
                    } else if (i % 3 == 2) {
                        byte[] bytes = Files.readAllBytes(hints.get(i));
                        bytes[bytes.length - 1] ^= 1;
                        Files.write(hints.get(i), bytes);
                    }
                }

                try (Bitcask store = new Bitcask(database, syncOnPut, 80)) {
                    assertValues(store, expected, keys);
                }
                assertDataUnchanged(originalData);
                for (Path hint : hintFiles(selected)) {
                    Files.delete(hint);
                }
                try (Bitcask store = new Bitcask(database, syncOnPut, 80)) {
                    assertValues(store, expected, keys);
                }
                assertTrue(hintFiles(selected).isEmpty());
                assertDataUnchanged(originalData);
            }
        }
    }

    @Test
    void shouldKeepLargeValuesAndEmptyKeys() throws IOException {
        String large = "café🚀".repeat(4000);
        String updated = "résumé🚀".repeat(3000);
        try (Bitcask store = new Bitcask(directory, false, 40_000)) {
            store.put("naïve", large);
            store.put("", large);
            store.put("naïve", updated);
            store.delete("");
            store.put("", "");
        }
        assertTrue(hintFiles(directory).size() >= 2);
        HintFileValidator.validate(directory, HintFileReader.read(directory, 0));
        Files.writeString(directory.resolve("1.hint"), "truncated hint");
        try (Bitcask store = new Bitcask(directory, false, 40_000)) {
            assertEquals(updated, store.get("naïve").orElseThrow());
            assertEquals("", store.get("").orElseThrow());
            store.compact();
        }
        Path selected = CompactionRecovery.recover(directory).getDataDirectory();
        for (Path hint : hintFiles(selected)) {
            Files.delete(hint);
        }
        try (Bitcask store = new Bitcask(directory, false, 40_000)) {
            assertEquals(updated, store.get("naïve").orElseThrow());
            assertEquals("", store.get("").orElseThrow());
            store.delete("naïve");
            store.compact();
        }
        try (Bitcask store = new Bitcask(directory, false, 40_000)) {
            assertTrue(store.get("naïve").isEmpty());
            assertEquals("", store.get("").orElseThrow());
        }
    }

    @Test
    void shouldRecoverAfterProcessExitWithTemporaryOrPublishedHints() throws Exception {
        for (String phase : new String[]{"temporary", "published"}) {
            Path database = Files.createDirectory(directory.resolve(phase));
            assertEquals(23, runCrashProcess(database, phase));
            Path temporary = database.resolve(".hint-3-interrupted.tmp");
            assertEquals(phase.equals("temporary"), Files.exists(temporary));
            if (phase.equals("published")) {
                HintFileValidator.validate(database, HintFileReader.read(database, 3));
            } else {
                assertFalse(Files.exists(database.resolve("3.hint")));
            }

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
            if (phase.equals("temporary")) {
                assertArrayEquals(new byte[]{0x42, 0x48}, Files.readAllBytes(temporary));
            }
        }
    }

    private void assertValues(Bitcask store, Map<String, String> expected, String[] keys) throws IOException {
        for (String key : keys) {
            assertEquals(Optional.ofNullable(expected.get(key)), store.get(key), "Unexpected value for key: " + key);
        }
        assertTrue(store.get("never-written").isEmpty());
    }

    private List<Path> hintFiles(Path dataDirectory) throws IOException {
        try (var paths = Files.newDirectoryStream(dataDirectory, "*.hint")) {
            List<Path> result = new ArrayList<>();
            for (Path path : paths) {
                result.add(path);
            }
            result.sort(Comparator.comparing(Path::toString));
            return result;
        }
    }

    private Map<Path, byte[]> dataSnapshot(Path dataDirectory) throws IOException {
        Map<Path, byte[]> snapshot = new HashMap<>();
        try (var paths = Files.newDirectoryStream(dataDirectory, "*.cask")) {
            for (Path path : paths) {
                snapshot.put(path, Files.readAllBytes(path));
            }
        }
        return snapshot;
    }

    private void assertDataUnchanged(Map<Path, byte[]> snapshot) throws IOException {
        for (var entry : snapshot.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        }
    }

    private int runCrashProcess(Path database, String phase) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath,
                HintCrashProcess.class.getName(), database.toString(), phase)
                .redirectErrorStream(true)
                .redirectOutput(database.resolve("hint-crash.log").toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Hint crash process timed out: " + phase);
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
