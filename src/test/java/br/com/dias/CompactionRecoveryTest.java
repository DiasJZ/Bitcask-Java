package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompactionRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void shouldIgnoreIncompleteOutputAndAnUnpublishedCurrentFile() throws IOException {
        createPlan();
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        Path partial = Files.createDirectory(directory.resolve(".compaction-incomplete"));
        Files.write(partial.resolve("0.cask"), new byte[3]);
        Files.writeString(directory.resolve(".current-interrupted.tmp"), "BITCASK_CURRENT_V1\n");

        CompactionState state = CompactionRecovery.recover(directory);

        assertEquals(directory.toRealPath(), state.getDataDirectory());
        assertEquals(0L, state.getFirstWritableFileId());
        assertFalse(Files.exists(directory.resolve("CURRENT")));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
        assertTrue(Files.exists(partial));
    }

    @Test
    void shouldIgnoreReadyOutputUntilTheSwitchIsCommitted() throws IOException {
        CompactionPlan plan = createPlan();
        Path output = prepareOutput(plan);

        CompactionState state = CompactionRecovery.recover(directory);

        assertEquals(directory.toRealPath(), state.getDataDirectory());
        assertTrue(Files.exists(output.resolve("READY")));
        assertFalse(Files.exists(directory.resolve("CURRENT")));
    }

    @Test
    void shouldRecoverCommittedFilesWithoutDependingOnOldFiles() throws IOException {
        CompactionPlan plan = createPlan();
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));
        Path output = prepareOutput(plan);

        CompactionRecovery.commit(directory, output);
        CompactionState state = CompactionRecovery.recover(directory);

        assertEquals(output.toRealPath(), state.getDataDirectory());
        assertEquals(2L, state.getFirstWritableFileId());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
        Map<String, EntryLocation> index = readIndex(state.getDataDirectory(), 2);
        assertEquals(plan.getKeys(), index.keySet());
        assertEquals("Bia", CaskFile.readAt(output, index.get("name")).getValue());
        assertFalse(index.containsKey("obsolete"));

        Files.delete(directory.resolve("0.cask"));
        assertEquals(output.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
        assertFalse(Files.exists(directory.resolve("0.cask")));
    }

    @Test
    void shouldKeepPreviousSelectionUntilTheNextCommitCompletes() throws IOException {
        CompactionPlan plan = createPlan();
        Path first = prepareOutput(plan);
        CompactionRecovery.commit(directory, first);
        byte[] previous = Files.readAllBytes(directory.resolve("CURRENT"));
        Path second = prepareOutput(plan);
        Files.writeString(directory.resolve(".current-interrupted.tmp"), "unfinished replacement");

        assertEquals(first.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("CURRENT")));

        CompactionRecovery.commit(directory, second);
        assertEquals(second.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
        assertEquals(second.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
        assertTrue(Files.exists(first.resolve("0.cask")));
        assertTrue(Files.exists(directory.resolve("0.cask")));
    }

    @Test
    void shouldFailOnCommittedCorruptionInsteadOfFallingBackToOldData() throws IOException {
        CompactionPlan plan = createPlan();
        Path output = prepareOutput(plan);
        CompactionRecovery.commit(directory, output);
        Path data = output.resolve("0.cask");
        byte[] valid = Files.readAllBytes(data);
        byte[] corrupted = valid.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(data, corrupted);

        assertThrows(IOException.class, () -> CompactionRecovery.recover(directory));
        assertArrayEquals(corrupted, Files.readAllBytes(data));

        Files.write(data, valid);
        Path ready = output.resolve("READY");
        byte[] validReady = Files.readAllBytes(ready);
        Files.writeString(ready, "BITCASK_COMPACTION_V1\n2\n");
        assertThrows(IOException.class, () -> CompactionRecovery.recover(directory));
        Files.write(ready, validReady);
        Files.delete(data);
        assertThrows(IOException.class, () -> CompactionRecovery.recover(directory));
        assertFalse(Files.exists(data));
        Files.write(data, valid);
        assertEquals(output.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
    }

    @Test
    void shouldRejectIncompleteCurrentAndAnInvalidPointerChecksum() throws IOException {
        CompactionPlan plan = createPlan();
        Path output = prepareOutput(plan);
        CompactionRecovery.commit(directory, output);
        Path current = directory.resolve("CURRENT");
        byte[] valid = Files.readAllBytes(current);
        Files.writeString(current, "BITCASK_CURRENT_V1\n");
        assertThrows(IOException.class, () -> CompactionRecovery.recover(directory));

        String changed = new String(valid, java.nio.charset.StandardCharsets.UTF_8)
                .replace(output.getFileName().toString(), ".compaction-other");
        Files.writeString(current, changed);
        assertThrows(IOException.class, () -> CompactionRecovery.recover(directory));
        assertEquals(changed, Files.readString(current));
        Files.write(current, valid);
        assertEquals(output.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
    }

    @Test
    void shouldPreserveCurrentWhenTheNextOutputIsNotReadyOrHasChanged() throws IOException {
        CompactionPlan plan = createPlan();
        Path first = prepareOutput(plan);
        CompactionRecovery.commit(directory, first);
        byte[] previous = Files.readAllBytes(directory.resolve("CURRENT"));

        Path unready = CompactionWriter.write(directory, plan, 40);
        assertThrows(IOException.class, () -> CompactionRecovery.commit(directory, unready));
        Path changed = prepareOutput(plan);
        Files.write(changed.resolve("0.cask"), new Entry(2000L, "name", "Eva").encode());
        assertThrows(IOException.class, () -> CompactionRecovery.commit(directory, changed));
        Path invalidManifest = prepareOutput(plan);
        Files.writeString(invalidManifest.resolve("READY"), "BITCASK_COMPACTION_V1\n2\nEND\n");
        assertThrows(IOException.class, () -> CompactionRecovery.commit(directory, invalidManifest));

        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("CURRENT")));
        assertEquals(first.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
    }

    @Test
    void shouldRecoverNewWritesWithoutModifyingTheCompactedBase() throws IOException {
        CompactionPlan plan = createPlan();
        Path output = prepareOutput(plan);
        CompactionRecovery.commit(directory, output);
        CompactionState state = CompactionRecovery.recover(directory);
        byte[] first = Files.readAllBytes(output.resolve("0.cask"));
        byte[] second = Files.readAllBytes(output.resolve("1.cask"));
        try (DataFileWriter writer = new DataFileWriter(output, state.getFirstWritableFileId(), 40)) {
            writer.append(new Entry(3000L, "name", "Clara"));
            writer.append(Entry.tombstone(3000L, "note"));
        }

        CompactionState recovered = CompactionRecovery.recover(directory);
        assertEquals(output.toRealPath(), recovered.getDataDirectory());
        assertEquals(2L, recovered.getFirstWritableFileId());
        Map<String, EntryLocation> index = readIndex(output, 4);
        assertEquals("Clara", CaskFile.readAt(output, index.get("name")).getValue());
        assertFalse(index.containsKey("note"));
        assertArrayEquals(first, Files.readAllBytes(output.resolve("0.cask")));
        assertArrayEquals(second, Files.readAllBytes(output.resolve("1.cask")));
    }

    private CompactionPlan createPlan() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile file = new CaskFile(directory, 0)) {
            file.append(new Entry(1000L, "name", "Ana"));
            file.append(new Entry(1000L, "obsolete", "old"));
            file.append(new Entry(2000L, "name", "Bia"));
            file.append(Entry.tombstone(2000L, "obsolete"));
            file.append(new Entry(2000L, "note", ""));
            index = file.loadIndex(0);
        }
        return new CompactionPlan(directory, index);
    }

    private Path prepareOutput(CompactionPlan plan) throws IOException {
        Path output = CompactionWriter.write(directory, plan, 40);
        CompactionPreparation.prepare(directory, output, plan, 40);
        return output;
    }

    private Map<String, EntryLocation> readIndex(Path dataDirectory, int count) throws IOException {
        Map<String, EntryLocation> index = new HashMap<>();
        for (int i = 0; i < count; i++) {
            try (CaskFile file = CaskFile.openReadOnly(dataDirectory, i)) {
                file.loadIndex(i, index);
            }
        }
        return index;
    }
}
