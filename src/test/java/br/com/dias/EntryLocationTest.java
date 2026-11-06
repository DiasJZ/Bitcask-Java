package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EntryLocationTest {
    @TempDir
    Path directory;

    @Test
    void shouldReadDifferentFilesWithTheSameOffset() throws IOException {
        EntryLocation first;
        EntryLocation second;
        try (DataFileWriter writer = new DataFileWriter(directory, 0, 35)) {
            first = writer.append(new Entry(1000L, "name", "Ana"));
            second = writer.append(new Entry(1001L, "name", "Bia"));
        }

        assertEquals(0L, first.getOffset());
        assertEquals(0L, second.getOffset());
        assertEquals(0L, first.getFileId());
        assertEquals(1L, second.getFileId());
        assertEquals("Ana", CaskFile.readAt(directory, first).getValue());
        assertEquals("Bia", CaskFile.readAt(directory, second).getValue());
    }

    @Test
    void shouldLoadLatestLocationsAndRemoveDeletedKeys() throws IOException {
        try (CaskFile file = new CaskFile(directory, 7)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(new Entry(1001L, "note", ""));
            long latestOffset = file.append(new Entry(1002L, "name", "Bia"));
            file.append(Entry.tombstone(1003L, "note"));

            Map<String, EntryLocation> index = file.loadIndex(7);
            assertEquals(1, index.size());
            EntryLocation location = index.get("name");
            assertEquals(7L, location.getFileId());
            assertEquals(latestOffset, location.getOffset());
            assertEquals("Bia", CaskFile.readAt(directory, location).getValue());
            assertFalse(index.containsKey("note"));
        }
    }

    @Test
    void shouldNotCreateMissingFilesWhenReadingLocations() {
        EntryLocation location = new EntryLocation(12, 0);
        assertThrows(IOException.class, () -> CaskFile.readAt(directory, location));
        assertFalse(Files.exists(directory.resolve("12.cask")));
    }
}
