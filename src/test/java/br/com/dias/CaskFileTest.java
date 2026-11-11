package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CaskFileTest {

    @TempDir
    Path directory;

    @Test
    void shouldAppendEntriesWithoutOverwriting() throws IOException {
        Path path = directory.resolve("data").resolve("0.cask");

        Entry first = new Entry(1000L, "name", "Ana");
        Entry second = new Entry(1001L, "city", "Recife");

        byte[] firstBytes = first.encode();
        byte[] secondBytes = second.encode();

        try (CaskFile file = new CaskFile(path)) {
            assertEquals(0L, file.append(first));
            assertArrayEquals(firstBytes, Files.readAllBytes(path));

            assertEquals(firstBytes.length, file.append(second));
        }

        byte[] stored = Files.readAllBytes(path);

        assertEquals(firstBytes.length + secondBytes.length, stored.length);

        byte[] storedFirst = Arrays.copyOfRange(stored, 0, firstBytes.length);
        byte[] storedSecond = Arrays.copyOfRange(stored, firstBytes.length, stored.length);
        assertArrayEquals(firstBytes, storedFirst);
        assertArrayEquals(secondBytes, storedSecond);
    }

    @Test
    void shouldWriteUtf8Entry() throws IOException {
        Path path = directory.resolve("utf8.cask");
        Entry entry = new Entry(1000L, "naïve", "café");

        try (CaskFile file = new CaskFile(path)) {
            assertEquals(0L, file.append(entry));
        }

        byte[] stored = Files.readAllBytes(path);

        assertEquals(39, stored.length);
        assertArrayEquals(entry.encode(), stored);
    }

    @Test
    void shouldDecodeEncodedEntry() {
        Entry original = new Entry(1000L, "naïve", "café");

        byte[] bytes = original.encode();
        Entry decoded = Entry.decode(bytes);

        assertEquals(original.getTimestamp(), decoded.getTimestamp());
        assertEquals(original.getKey(), decoded.getKey());
        assertEquals(original.getValue(), decoded.getValue());
    }

    @Test
    void shouldReadEntryAfterReopeningFile() throws IOException {
        Path path = directory.resolve("read.cask");
        long offset;
        long firstOffset;

        try (CaskFile file = new CaskFile(path)) {
            firstOffset = file.append(new Entry(1000L, "name", "Ana"));
            offset = file.append(new Entry(2000L, "drink", "café"));
        }

        try (CaskFile file = new CaskFile(path)) {
            Entry entry = file.read(offset);

            assertEquals(2000L, entry.getTimestamp());
            assertEquals("drink", entry.getKey());
            assertEquals("café", entry.getValue());
            Entry first = file.read(firstOffset);

            assertEquals(1000L, first.getTimestamp());
            assertEquals("name", first.getKey());
            assertEquals("Ana", first.getValue());
        }
    }
}
