package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BitcaskRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void shouldRecoverRotatedFilesAndContinueWriting() throws IOException {
        byte[] firstFile;
        try (Bitcask store = new Bitcask(directory, true, 40)) {
            store.put("name", "Ana");
            firstFile = Files.readAllBytes(directory.resolve("0.cask"));
            store.put("drink", "coffee");
            store.put("name", "Bia");
            store.delete("drink");
            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("drink").isEmpty());
            assertTrue(Files.exists(directory.resolve("3.cask")));
        }

        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("drink").isEmpty());
            store.put("drink", "tea");
            store.put("note", "");
            assertTrue(Files.exists(directory.resolve("5.cask")));
            assertEquals("tea", store.get("drink").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
            store.sync();
        }

        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertEquals("tea", store.get("drink").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
        }
        assertArrayEquals(firstFile, Files.readAllBytes(directory.resolve("0.cask")));
    }

    @Test
    void shouldReplayFilesInNumericOrderInsteadOfTimestampOrder() throws IOException {
        try (CaskFile file = new CaskFile(directory, 2)) {
            file.append(new Entry(9000L, "name", "Ana"));
            file.append(new Entry(9000L, "note", "old"));
        }
        try (CaskFile file = new CaskFile(directory, 10)) {
            file.append(new Entry(1000L, "name", "Bia"));
            file.append(Entry.tombstone(1000L, "note"));
        }
        byte[] oldFile = Files.readAllBytes(directory.resolve("2.cask"));
        long previousSize = Files.size(directory.resolve("10.cask"));

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertTrue(store.get("note").isEmpty());
            store.put("city", "Recife");
        }

        assertFalse(Files.exists(directory.resolve("0.cask")));
        assertTrue(Files.size(directory.resolve("10.cask")) > previousSize);
        assertArrayEquals(oldFile, Files.readAllBytes(directory.resolve("2.cask")));
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Recife", store.get("city").orElseThrow());
        }
    }

    @Test
    void shouldResumeAnEmptyLastFileAndIgnoreUnrelatedFiles() throws IOException {
        Files.write(directory.resolve("2.cask"), new Entry(1000L, "name", "Ana").encode());
        Files.createFile(directory.resolve("7.cask"));
        Files.writeString(directory.resolve("notes.txt"), "keep this file");
        Files.writeString(directory.resolve("backup.cask"), "unrelated file");

        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            store.put("name", "Bia");
            assertEquals(35L, Files.size(directory.resolve("7.cask")));
            assertFalse(Files.exists(directory.resolve("8.cask")));
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Bia", store.get("name").orElseThrow());
        }
        assertEquals("keep this file", Files.readString(directory.resolve("notes.txt")));
        assertEquals("unrelated file", Files.readString(directory.resolve("backup.cask")));
    }

    @Test
    void shouldReleaseLockAfterCorruptionInAnOlderFile() throws IOException {
        byte[] valid = new Entry(1000L, "name", "Ana").encode();
        byte[] corrupted = valid.clone();
        corrupted[corrupted.length - 1] ^= 1;
        byte[] latest = new Entry(2000L, "name", "Bia").encode();
        Files.write(directory.resolve("0.cask"), corrupted);
        Files.write(directory.resolve("1.cask"), latest);

        assertThrows(IllegalArgumentException.class, () -> new Bitcask(directory));
        assertArrayEquals(corrupted, Files.readAllBytes(directory.resolve("0.cask")));
        assertArrayEquals(latest, Files.readAllBytes(directory.resolve("1.cask")));

        Files.write(directory.resolve("0.cask"), valid);
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldRejectAmbiguousFileNamesAndReleaseLock() throws IOException {
        Path invalidFile = directory.resolve("01.cask");
        Files.write(invalidFile, new Entry(1000L, "name", "Ana").encode());
        assertThrows(IOException.class, () -> new Bitcask(directory));

        Files.move(invalidFile, directory.resolve("1.cask"));
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }
}
