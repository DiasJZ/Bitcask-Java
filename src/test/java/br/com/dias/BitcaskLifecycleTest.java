package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BitcaskLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void shouldRejectAnotherWriterAndAllowReopeningAfterClose() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            for (int i = 0; i < 2; i++) {
                IOException error = assertThrows(IOException.class, () -> new Bitcask(directory));
                assertEquals("Bitcask is already open for writing", error.getMessage());
            }
            store.put("name", "Ana");
            assertEquals("Ana", store.get("name").orElseThrow());
        }

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldAllowDifferentDatabasesAtTheSameTime() throws IOException {
        try (Bitcask first = new Bitcask(directory.resolve("first"));
             Bitcask second = new Bitcask(directory.resolve("second"))) {
            first.put("name", "Ana");
            second.put("name", "Bia");
            assertEquals("Ana", first.get("name").orElseThrow());
            assertEquals("Bia", second.get("name").orElseThrow());
        }
    }

    @Test
    void shouldReleaseLockWhenReadingExistingDataFails() throws IOException {
        Path data = directory.resolve("0.cask");
        Files.write(data, new byte[3]);
        assertThrows(IOException.class, () -> new Bitcask(directory));

        byte[] corrupted = new Entry(1000L, "name", "Ana").encode();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(data, corrupted);
        assertThrows(IllegalArgumentException.class, () -> new Bitcask(directory));

        Files.write(data, new Entry(1000L, "name", "Ana").encode());
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldReleaseLockWhenOpeningDataFileFails() throws IOException {
        Path data = directory.resolve("0.cask");
        Files.createDirectory(data);
        assertThrows(IOException.class, () -> new Bitcask(directory));
        Files.delete(data);

        try (Bitcask store = new Bitcask(directory)) {
            store.put("name", "Ana");
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldSyncAndContinueWriting() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.sync();
            store.put("name", "Ana");
            store.sync();
            store.delete("name");
            store.put("drink", "coffee");
            store.sync();
            assertTrue(store.get("name").isEmpty());
            assertEquals("coffee", store.get("drink").orElseThrow());
        }

        try (Bitcask store = new Bitcask(directory)) {
            assertTrue(store.get("name").isEmpty());
            assertEquals("coffee", store.get("drink").orElseThrow());
        }
    }

    @Test
    void shouldSupportSyncAfterEachWrite() throws IOException {
        try (Bitcask store = new Bitcask(directory, true)) {
            store.put("name", "Ana");
            store.put("name", "Bia");
            store.put("note", "");
            store.delete("name");
        }

        try (Bitcask store = new Bitcask(directory, true)) {
            assertTrue(store.get("name").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
            store.put("name", "Clara");
            assertEquals("Clara", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldHandleClosingTwice() throws IOException {
        Bitcask store = new Bitcask(directory);
        store.put("name", "Ana");
        store.close();
        store.close();

        assertThrows(IOException.class, () -> store.get("name"));
        assertThrows(IOException.class, () -> store.get("missing"));
        assertThrows(IOException.class, () -> store.put("name", "Bia"));
        assertThrows(IOException.class, () -> store.delete("name"));
        assertThrows(IOException.class, store::sync);

        try (Bitcask reopened = new Bitcask(directory)) {
            assertEquals("Ana", reopened.get("name").orElseThrow());
        }
    }

    @Test
    void shouldBlockAnotherJavaProcessUntilClose() throws Exception {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("name", "Ana");
            assertEquals(2, runWriterProcess());
            assertTrue(Files.readString(directory.resolve("process.log"))
                    .contains("Bitcask is already open for writing"));
            assertEquals("Ana", store.get("name").orElseThrow());
        }

        assertEquals(0, runWriterProcess());
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("ok", store.get("process").orElseThrow());
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }

    private int runWriterProcess() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath,
                WriterProcess.class.getName(), directory.toString())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("process.log").toFile())
                .start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Writer process timed out");
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
