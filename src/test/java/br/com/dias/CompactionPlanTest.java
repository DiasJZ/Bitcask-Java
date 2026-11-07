package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompactionPlanTest {
    @TempDir
    Path directory;

    @Test
    void shouldSelectOnlyCurrentRecordsWithoutChangingSourceFiles() throws IOException {
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(new Entry(1000L, "drink", "coffee"));
            file.append(new Entry(1000L, "naïve", "café"));
        }
        try (CaskFile file = new CaskFile(directory, 1)) {
            file.append(new Entry(2000L, "name", "Bia"));
            file.append(Entry.tombstone(2000L, "drink"));
            file.append(Entry.tombstone(2000L, "name"));
        }
        try (CaskFile file = new CaskFile(directory, 2)) {
            file.append(new Entry(3000L, "name", "Clara"));
            file.append(new Entry(3000L, "note", ""));
            file.append(new Entry(3000L, "", "empty key"));
        }

        Map<String, EntryLocation> index = new HashMap<>();
        Map<Long, byte[]> originalFiles = new HashMap<>();
        for (long fileId = 0; fileId < 3; fileId++) {
            originalFiles.put(fileId, Files.readAllBytes(directory.resolve(fileId + ".cask")));
            try (CaskFile file = CaskFile.openReadOnly(directory, fileId)) {
                file.loadIndex(fileId, index);
            }
        }

        CompactionPlan plan = new CompactionPlan(directory, index);
        assertEquals(Set.of("name", "naïve", "note", ""), plan.getKeys());
        assertEquals("Clara", plan.readEntry("name").getValue());
        assertEquals(3000L, plan.readEntry("name").getTimestamp());
        assertEquals("café", plan.readEntry("naïve").getValue());
        assertEquals("", plan.readEntry("note").getValue());
        assertEquals("empty key", plan.readEntry("").getValue());
        assertThrows(IllegalArgumentException.class, () -> plan.readEntry("drink"));

        for (long fileId = 0; fileId < 3; fileId++) {
            assertArrayEquals(originalFiles.get(fileId), Files.readAllBytes(directory.resolve(fileId + ".cask")));
        }
        try (var paths = Files.list(directory)) {
            assertEquals(3L, paths.count());
        }
    }

    @Test
    void shouldKeepSelectionStableWhenOriginalIndexChanges() throws IOException {
        long firstOffset;
        long secondOffset;
        try (CaskFile file = new CaskFile(directory, 0)) {
            firstOffset = file.append(new Entry(1000L, "name", "Ana"));
            secondOffset = file.append(new Entry(2000L, "name", "Bia"));
        }
        Map<String, EntryLocation> index = new HashMap<>();
        index.put("name", new EntryLocation(0, firstOffset));
        CompactionPlan plan = new CompactionPlan(directory, index);
        index.put("name", new EntryLocation(0, secondOffset));
        index.clear();

        assertEquals(Set.of("name"), plan.getKeys());
        assertEquals("Ana", plan.readEntry("name").getValue());
        assertThrows(UnsupportedOperationException.class, () -> plan.getKeys().clear());
    }

    @Test
    void shouldSelectNothingWhenAllKeysWereDeleted() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(Entry.tombstone(2000L, "name"));
            file.append(Entry.tombstone(2001L, "missing"));
            index = file.loadIndex(0);
        }
        CompactionPlan plan = new CompactionPlan(directory, index);
        assertTrue(plan.getKeys().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> plan.readEntry("name"));
        assertTrue(new CompactionPlan(directory, Map.of()).getKeys().isEmpty());
    }

    @Test
    void shouldRejectLocationsPointingToAnotherKeyOrADeletion() throws IOException {
        long liveOffset;
        long deletedOffset;
        try (CaskFile file = new CaskFile(directory, 0)) {
            liveOffset = file.append(new Entry(1000L, "name", "Ana"));
            deletedOffset = file.append(Entry.tombstone(2000L, "name"));
        }
        CompactionPlan wrongKey = new CompactionPlan(directory,
                Map.of("city", new EntryLocation(0, liveOffset)));
        CompactionPlan deleted = new CompactionPlan(directory,
                Map.of("name", new EntryLocation(0, deletedOffset)));

        assertThrows(IOException.class, () -> wrongKey.readEntry("city"));
        assertThrows(IOException.class, () -> deleted.readEntry("name"));
    }

    @Test
    void shouldRejectCorruptedSelectedRecordWithoutChangingIt() throws IOException {
        byte[] record = new Entry(1000L, "name", "Ana").encode();
        record[record.length - 1] ^= 1;
        Path path = directory.resolve("0.cask");
        Files.write(path, record);
        CompactionPlan plan = new CompactionPlan(directory,
                Map.of("name", new EntryLocation(0, 0)));

        assertThrows(IllegalArgumentException.class, () -> plan.readEntry("name"));
        assertArrayEquals(record, Files.readAllBytes(path));
    }
}
