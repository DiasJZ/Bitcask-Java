package br.com.dias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void shouldGenerateHintsOnlyForFilesSealedByRotation() throws IOException {
        try (DataFileWriter writer = new DataFileWriter(directory, 0, 40)) {
            writer.append(new Entry(1000L, "name", "Ana"));
            assertFalse(Files.exists(directory.resolve("0.hint")));
            writer.append(Entry.tombstone(2000L, "name"));
            HintFile first = validatedHint(directory, 0);
            assertEquals("name", first.getEntries().get(0).getKey());
            assertFalse(first.getEntries().get(0).isDeleted());
            byte[] unchanged = Files.readAllBytes(directory.resolve("0.hint"));
            assertFalse(Files.exists(directory.resolve("1.hint")));

            writer.append(new Entry(3000L, "drink", "tea"));

            assertTrue(validatedHint(directory, 1).getEntries().get(0).isDeleted());
            assertArrayEquals(unchanged, Files.readAllBytes(directory.resolve("0.hint")));
            assertFalse(Files.exists(directory.resolve("2.hint")));
        }
        assertFalse(Files.exists(directory.resolve("2.hint")));
    }

    @Test
    void shouldKeepTheNewWriterUsableIfRotatedHintPublicationFails() throws IOException {
        Path occupied = Files.createDirectory(directory.resolve("0.hint"));
        Files.writeString(occupied.resolve("keep.txt"), "keep");
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            byte[] original = Files.readAllBytes(directory.resolve("0.cask"));

            assertThrows(IOException.class, () -> store.put("city", "Rio"));

            assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
            assertEquals(0L, Files.size(directory.resolve("1.cask")));
            assertEquals("Ana", store.get("name").orElseThrow());
            assertTrue(store.get("city").isEmpty());
            assertEquals("keep", Files.readString(occupied.resolve("keep.txt")));
            try (var files = Files.newDirectoryStream(directory, ".hint-*.tmp")) {
                assertFalse(files.iterator().hasNext());
            }

            store.put("city", "Rio");
            assertEquals("Rio", store.get("city").orElseThrow());
        }
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            assertEquals("Rio", store.get("city").orElseThrow());
        }
    }

    @Test
    void shouldGenerateCompactedHintsAndRemoveHintsFromPreviousGenerations() throws IOException {
        Files.writeString(directory.resolve("notes.hint"), "unrelated");
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            store.put("name", "Ana");
            store.put("drink", "tea");
            store.put("name", "Bia");
            assertTrue(Files.exists(directory.resolve("0.hint")));
            assertTrue(Files.exists(directory.resolve("1.hint")));

            store.compact();

            CompactionState first = CompactionRecovery.recover(directory);
            assertBaseHints(first);
            assertFalse(Files.exists(directory.resolve("0.hint")));
            assertFalse(Files.exists(directory.resolve("1.hint")));
            assertFalse(Files.exists(directory.resolve("0.cask")));
            assertFalse(Files.exists(first.getDataDirectory().resolve(first.getFirstWritableFileId() + ".hint")));
            store.put("name", "Clara");
            store.compact();

            assertFalse(Files.exists(first.getDataDirectory()));
            assertBaseHints(CompactionRecovery.recover(directory));
            assertEquals("Clara", store.get("name").orElseThrow());
            assertEquals("tea", store.get("drink").orElseThrow());
        }
        assertEquals("unrelated", Files.readString(directory.resolve("notes.hint")));
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Clara", store.get("name").orElseThrow());
        }
    }

    @Test
    void shouldGenerateAnEmptyHintForAnEmptyCompactedBase() throws IOException {
        try (Bitcask store = new Bitcask(directory)) {
            store.put("name", "Ana");
            store.delete("name");
            store.compact();

            Path selected = CompactionRecovery.recover(directory).getDataDirectory();
            HintFile hint = validatedHint(selected, 0);
            assertTrue(hint.getEntries().isEmpty());
            assertEquals(40L, Files.size(selected.resolve("0.hint")));
            assertTrue(store.get("name").isEmpty());
        }
    }

    @Test
    void shouldRemoveGeneratedHintsWhenLaterCompactionWritingFails() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile source = new CaskFile(directory, 0)) {
            source.append(new Entry(1000L, "a", "first"));
            source.append(new Entry(1000L, "b", "second"));
            source.append(new Entry(1000L, "z", "x".repeat(100)));
            index = source.loadIndex(0);
        }
        byte[] original = Files.readAllBytes(directory.resolve("0.cask"));

        assertThrows(IOException.class,
                () -> CompactionWriter.write(directory, new CompactionPlan(directory, index), 40));

        assertArrayEquals(original, Files.readAllBytes(directory.resolve("0.cask")));
        try (var paths = Files.list(directory)) {
            assertEquals(Set.of("0.cask"), paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
    }

    @Test
    void shouldKeepHintsOptionalWhenPreparingAndCommittingCompaction() throws IOException {
        CompactionPlan plan = createPlan();
        Path output = CompactionWriter.write(directory, plan, 40);
        Files.delete(output.resolve("0.hint"));
        Files.writeString(output.resolve("1.hint"), "damaged hint");

        CompactionPreparation.prepare(directory, output, plan, 40);
        CompactionRecovery.commit(directory, output);

        assertEquals(output.toRealPath(), CompactionRecovery.recover(directory).getDataDirectory());
        try (Bitcask store = new Bitcask(directory, false, 40)) {
            assertEquals("Ana", store.get("name").orElseThrow());
            assertEquals("", store.get("note").orElseThrow());
        }
    }

    @Test
    void shouldRejectUnrelatedHintsDuringCompactionPreparation() throws IOException {
        CompactionPlan plan = createPlan();
        for (String name : new String[]{"9.hint", "01.hint", "notes.hint"}) {
            Path output = CompactionWriter.write(directory, plan, 40);
            Files.writeString(output.resolve(name), "unexpected");

            assertThrows(IOException.class, () -> CompactionPreparation.prepare(directory, output, plan, 40));

            assertFalse(Files.exists(output.resolve("READY")));
            assertFalse(Files.exists(directory.resolve("CURRENT")));
        }
    }

    @Test
    void shouldRejectUnrelatedHintsBeforeCommittingPreparedOutput() throws IOException {
        CompactionPlan plan = createPlan();
        for (String name : new String[]{"9.hint", "01.hint", "notes.hint"}) {
            Path output = CompactionWriter.write(directory, plan, 40);
            CompactionPreparation.prepare(directory, output, plan, 40);
            Files.writeString(output.resolve(name), "unexpected");

            assertThrows(IOException.class, () -> CompactionRecovery.commit(directory, output));

            assertFalse(Files.exists(directory.resolve("CURRENT")));
        }
    }

    private HintFile validatedHint(Path dataDirectory, long fileId) throws IOException {
        HintFile hint = HintFileReader.read(dataDirectory, fileId);
        HintFileValidator.validate(dataDirectory, hint);
        return hint;
    }

    private void assertBaseHints(CompactionState state) throws IOException {
        assertTrue(state.getFirstWritableFileId() > 0);
        for (long fileId = 0; fileId < state.getFirstWritableFileId(); fileId++) {
            HintFile hint = validatedHint(state.getDataDirectory(), fileId);
            assertTrue(hint.getEntries().stream().noneMatch(HintEntry::isDeleted));
        }
    }

    private CompactionPlan createPlan() throws IOException {
        Map<String, EntryLocation> index;
        try (CaskFile source = new CaskFile(directory, 0)) {
            source.append(new Entry(1000L, "name", "Ana"));
            source.append(new Entry(2000L, "note", ""));
            index = source.loadIndex(0);
        }
        return new CompactionPlan(directory, index);
    }
}
