package br.com.dias;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

final class CompactionPlan {
    private final Path directory;
    private final Map<String, EntryLocation> entries;

    CompactionPlan(Path directory, Map<String, EntryLocation> index) {
        this.directory = directory;
        entries = Map.copyOf(index);
    }

    Set<String> getKeys() {
        return entries.keySet();
    }

    Entry readEntry(String key) throws IOException {
        EntryLocation location = entries.get(key);
        if (location == null) {
            throw new IllegalArgumentException("Key is not part of the compaction plan");
        }
        Entry entry = CaskFile.readAt(directory, location);
        if (!key.equals(entry.getKey()) || entry.isDeleted()) {
            throw new IOException("Index does not point to a live record for key: " + key);
        }
        return entry;
    }
}
