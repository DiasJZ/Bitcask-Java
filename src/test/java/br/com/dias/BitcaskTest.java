package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BitcaskTest {
    @TempDir
    Path directory;

    @Test
    void shouldKeepDeletionAfterReopeningWithoutOverwritingOldRecords() throws IOException {
        Path path = directory.resolve("0.cask");
        byte[] beforeDeletion;
        try (Bitcask store = new Bitcask(directory)) {
            store.put("action", "write");
            store.put("action", "read");
            store.put("note", "");
            beforeDeletion = Files.readAllBytes(path);

            store.delete("action");

            assertTrue(store.get("action").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
            store.put("drink", "coffee");
        }

        byte[] afterDeletion = Files.readAllBytes(path);
        assertTrue(afterDeletion.length > beforeDeletion.length);
        assertArrayEquals(beforeDeletion, Arrays.copyOf(afterDeletion, beforeDeletion.length));

        try (Bitcask store = new Bitcask(directory)) {
            assertTrue(store.get("action").isEmpty());
            assertEquals("", store.get("note").orElseThrow());
            assertEquals("coffee", store.get("drink").orElseThrow());
        }
    }

    @Test
    void shouldAllowRepeatedDeletionAndMissingKeys() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.delete("missing");
            store.put("name", "Ana");
            store.delete("name");
            store.delete("name");
        }

        try (Bitcask store = new Bitcask(directory)) {
            assertTrue(store.get("missing").isEmpty());
            assertTrue(store.get("name").isEmpty());
        }
    }

    @Test
    void shouldAllowWritingDeletedKeyAgain() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("name", "Ana");
            store.delete("name");
        }

        try (Bitcask store = new Bitcask(directory)) {
            assertTrue(store.get("name").isEmpty());
            store.put("name", "");
            assertEquals("", store.get("name").orElseThrow());
        }

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldRejectNullValueWithoutDeletingExistingKey() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("name", "Ana");
            assertThrows(NullPointerException.class, () -> store.put("name", null));
            assertEquals("Ana", store.get("name").orElseThrow());
        }

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Ana", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldDistinguishEmptyValueFromMissingKey() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("note", "");
            assertEquals("", store.get("note").orElseThrow());
            assertTrue(store.get("missing").isEmpty());
        }
    }

    @Test
    void shouldReturnLatestValueForEachKey() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("name", "Ana");
            store.put("drink", "coffee");
            store.put("name", "Bia");

            assertEquals("Bia", store.get("name").orElseThrow());
            assertEquals("coffee", store.get("drink").orElseThrow());
        }
    }

    @Test
    void shouldRebuildIndexWhenReopened() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("action", "write");
            store.put("drink", "coffee");
            store.put("action", "read");
        }

        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("read", store.get("action").orElseThrow());
            assertEquals("coffee", store.get("drink").orElseThrow());
            store.put("drink", "tea");
            assertEquals("tea", store.get("drink").orElseThrow());
        }
    }
}
