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

public class DataFileWriterTest {
    @TempDir
    Path directory;

    @Test
    void shouldFillFileExactlyBeforeRotating() throws IOException {
        Entry entry = new Entry(1000L, "name", "Ana");
        int size = entry.encode().length;
        try (DataFileWriter writer = new DataFileWriter(directory, 0, size * 2L)) {
            assertEquals(0L, writer.append(entry));
            assertEquals(size, writer.append(entry));
            assertEquals(0L, writer.getActiveFileId());
            assertFalse(Files.exists(directory.resolve("1.cask")));
            byte[] original = Files.readAllBytes(directory.resolve("0.cask"));

            assertEquals(0L, writer.append(entry));
            assertEquals(1L, writer.getActiveFileId());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
            assertArrayEquals(entry.encode(), Files.readAllBytes(directory.resolve("1.cask")));
        }
    }

    @Test
    void shouldRotateBeforeOverflowAndKeepWholeRecords() throws IOException {
        Entry first = new Entry(1000L, "name", "Ana");
        Entry second = new Entry(1001L, "name", "Beatrice");
        Entry deletion = Entry.tombstone(1002L, "name");
        try (DataFileWriter writer = new DataFileWriter(directory, 0, 50)) {
            writer.append(first);
            assertEquals(0L, writer.append(second));
            assertEquals(1L, writer.getActiveFileId());
            assertEquals(0L, writer.append(deletion));
            assertEquals(2L, writer.getActiveFileId());
        }
        assertArrayEquals(first.encode(), Files.readAllBytes(directory.resolve("0.cask")));
        assertArrayEquals(second.encode(), Files.readAllBytes(directory.resolve("1.cask")));
        assertArrayEquals(deletion.encode(), Files.readAllBytes(directory.resolve("2.cask")));
    }

    @Test
    void shouldRejectOversizedRecordWithoutChangingFiles() throws IOException {
        Entry small = new Entry(1000L, "name", "Ana");
        Entry large = new Entry(1001L, "name", "a".repeat(100));
        try (DataFileWriter writer = new DataFileWriter(directory, 0, 50)) {
            assertThrows(IOException.class, () -> writer.append(large));
            assertEquals(0L, Files.size(directory.resolve("0.cask")));
            writer.append(small);
            assertThrows(IOException.class, () -> writer.append(large));
            assertEquals(0L, writer.getActiveFileId());
            assertFalse(Files.exists(directory.resolve("1.cask")));
            assertArrayEquals(small.encode(), Files.readAllBytes(directory.resolve("0.cask")));
        }
    }

    @Test
    void shouldNeverOverwriteAnExistingNextFile() throws IOException {
        Entry entry = new Entry(1000L, "name", "Ana");
        byte[] existing = new Entry(2000L, "name", "Bia").encode();
        Files.write(directory.resolve("1.cask"), existing);
        try (DataFileWriter writer = new DataFileWriter(directory, 0, 40)) {
            writer.append(entry);
            assertThrows(IOException.class, () -> writer.append(entry));
            assertEquals(0L, writer.getActiveFileId());
            assertArrayEquals(existing, Files.readAllBytes(directory.resolve("1.cask")));
            assertArrayEquals(entry.encode(), Files.readAllBytes(directory.resolve("0.cask")));
            writer.sync();
        }
    }

    @Test
    void shouldResumeTheSpecifiedFileAndRejectWritesAfterClose() throws IOException {
        Entry entry = new Entry(1000L, "name", "Ana");
        try (DataFileWriter writer = new DataFileWriter(directory, 2, 70)) {
            writer.append(entry);
        }
        DataFileWriter writer = new DataFileWriter(directory, 2, 70);
        try (writer) {
            assertEquals(35L, writer.append(entry));
            assertEquals(0L, writer.append(entry));
            assertEquals(3L, writer.getActiveFileId());
        }
        writer.close();
        assertThrows(IOException.class, () -> writer.append(entry));
        assertThrows(IOException.class, writer::sync);
        assertEquals(70L, Files.size(directory.resolve("2.cask")));
        assertArrayEquals(entry.encode(), Files.readAllBytes(directory.resolve("3.cask")));
    }
}
