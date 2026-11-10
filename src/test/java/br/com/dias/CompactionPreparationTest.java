package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CompactionPreparationTest {
    @TempDir
    Path directory;

    @Test
    void shouldMarkValidatedOutputReadyWithoutReplacingOriginals() throws IOException {
        CompactionPlan plan = createPlan();
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        Path output = CompactionWriter.write(directory, plan, 40);
        byte[] first = Files.readAllBytes(output.resolve("0.cask"));
        byte[] second = Files.readAllBytes(output.resolve("1.cask"));

        Path ready = CompactionPreparation.prepare(directory, output, plan, 40);

        assertEquals(output.resolve("READY"), ready);
        List<String> lines = Files.readAllLines(ready);
        assertEquals(5, lines.size());
        assertEquals("BITCASK_COMPACTION_V1", lines.get(0));
        assertEquals("2", lines.get(1));
        CRC32 crc = new CRC32();
        crc.update(first);
        assertEquals("0 " + first.length + " " + crc.getValue(), lines.get(2));
        crc.reset();
        crc.update(second);
        assertEquals("1 " + second.length + " " + crc.getValue(), lines.get(3));
        assertEquals("END", lines.get(4));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
        assertArrayEquals(first, Files.readAllBytes(output.resolve("0.cask")));
        assertArrayEquals(second, Files.readAllBytes(output.resolve("1.cask")));
        try (var files = Files.list(output)) {
            assertEquals(5L, files.count());
        }
        try (Bitcask store = new Bitcask(directory)) {
            assertEquals("Bia", store.get("name").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
        }
    }

    @Test
    void shouldPrepareAnEmptyCompaction() throws IOException {
        CompactionPlan plan = new CompactionPlan(directory, Map.of());
        Path output = CompactionWriter.write(directory, plan, 40);

        Path ready = CompactionPreparation.prepare(directory, output, plan, 40);

        assertEquals("BITCASK_COMPACTION_V1\n1\n0 0 0\nEND\n", Files.readString(ready));
        assertFalse(Files.exists(directory.resolve("0.cask")));
    }

    @Test
    void shouldRejectMissingRecordsAndTruncatedOutput() throws IOException {
        CompactionPlan plan = createPlan();
        Path missing = CompactionWriter.write(directory, plan, 40);
        Files.delete(missing.resolve("1.cask"));
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, missing, plan, 40));
        assertFalse(Files.exists(missing.resolve("READY")));

        Path truncated = CompactionWriter.write(directory, plan, 40);
        Path path = truncated.resolve("0.cask");
        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, truncated, plan, 40));
        assertFalse(Files.exists(truncated.resolve("READY")));
    }

    @Test
    void shouldRejectChangedValuesAndDeletionRecordsEvenWithValidChecksums() throws IOException {
        CompactionPlan plan = createPlan();
        Path changed = CompactionWriter.write(directory, plan, 40);
        Files.write(changed.resolve("0.cask"), new Entry(2000L, "name", "Eva").encode());
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, changed, plan, 40));
        assertFalse(Files.exists(changed.resolve("READY")));

        Path deleted = CompactionWriter.write(directory, plan, 40);
        Files.write(deleted.resolve("0.cask"), Entry.tombstone(2000L, "name").encode());
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, deleted, plan, 40));
        assertFalse(Files.exists(deleted.resolve("READY")));
    }

    @Test
    void shouldRejectDuplicateAndUnexpectedRecords() throws IOException {
        CompactionPlan plan = createPlan();
        Path duplicate = CompactionWriter.write(directory, plan, 40);
        Files.write(duplicate.resolve("2.cask"), plan.readEntry("name").encode());
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, duplicate, plan, 40));
        assertFalse(Files.exists(duplicate.resolve("READY")));

        Path extra = CompactionWriter.write(directory, plan, 40);
        Files.write(extra.resolve("2.cask"), new Entry(1000L, "city", "Recife").encode());
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, extra, plan, 40));
        assertFalse(Files.exists(extra.resolve("READY")));
    }

    @Test
    void shouldRejectFileGapsAndOversizedFiles() throws IOException {
        CompactionPlan plan = createPlan();
        Path gap = CompactionWriter.write(directory, plan, 40);
        Files.move(gap.resolve("1.cask"), gap.resolve("2.cask"));
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, gap, plan, 40));
        assertFalse(Files.exists(gap.resolve("READY")));

        Path oversized = CompactionWriter.write(directory, plan, 100);
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, oversized, plan, 40));
        assertFalse(Files.exists(oversized.resolve("READY")));
    }

    @Test
    void shouldRejectTheSourceDirectoryAndPreserveExistingReadyMarker() throws IOException {
        CompactionPlan plan = createPlan();
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, directory, plan, 40));
        assertFalse(Files.exists(directory.resolve("READY")));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));

        Path output = CompactionWriter.write(directory, plan, 40);
        Path ready = CompactionPreparation.prepare(directory, output, plan, 40);
        byte[] marker = Files.readAllBytes(ready);
        assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, output, plan, 40));
        assertArrayEquals(marker, Files.readAllBytes(ready));
    }

    private CompactionPlan createPlan() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(new Entry(2000L, "name", "Bia"));
            file.append(new Entry(2000L, "note", ""));
            index = file.loadIndex(0);
        }
        return new CompactionPlan(directory, index);
    }
}
