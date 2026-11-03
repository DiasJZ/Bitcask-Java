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

        Entry first = new Entry(1000L, "nome", "Ana");
        Entry second = new Entry(1001L, "cidade", "Recife");

        byte[] firstBytes = first.encode();
        byte[] secondBytes = second.encode();

        try (CaskFile file = new CaskFile(path)) {
            assertEquals(0L, file.append(first));
            assertArrayEquals(firstBytes, Files.readAllBytes(path));

            assertEquals(firstBytes.length, file.append(second));
        }


        byte[] stored = Files.readAllBytes(path);

        assertEquals(firstBytes.length + secondBytes.length, stored.length);

        assertArrayEquals(
                firstBytes,
                Arrays.copyOfRange(stored, 0, firstBytes.length)
        );

        assertArrayEquals(
                secondBytes,
                Arrays.copyOfRange(stored, firstBytes.length, stored.length)
        );
    }

    @Test
    void shouldWriteUtf8Entry() throws IOException {
        Path path = directory.resolve("utf8.cask");
        Entry entry = new Entry(1000L, "ação", "café");

        try (CaskFile file = new CaskFile(path)) {
            assertEquals(0L, file.append(entry));
        }

        byte[] stored = Files.readAllBytes(path);

        assertEquals(39, stored.length);
        assertArrayEquals(entry.encode(), stored);
    }

    @Test
    void shouldDecodeEncodedEntry() {
        Entry original = new Entry(1000L, "ação", "café");

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
            firstOffset = file.append(new Entry(1000L, "nome", "Ana"));
            offset = file.append(new Entry(2000L, "bebida", "café"));
        }

        try (CaskFile file = new CaskFile(path)) {
            Entry entry = file.read(offset);

            assertEquals(2000L, entry.getTimestamp());
            assertEquals("bebida", entry.getKey());
            assertEquals("café", entry.getValue());
            Entry first = file.read(firstOffset);

            assertEquals(1000L, first.getTimestamp());
            assertEquals("nome", first.getKey());
            assertEquals("Ana", first.getValue());
        }
    }
}