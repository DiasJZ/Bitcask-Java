package br.com.dias;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class HintIndexLoader {
    private HintIndexLoader() {
    }

    static boolean load(Path directory, long fileId, Map<String, EntryLocation> index) throws IOException {
        if (fileId < 0) {
            throw new IllegalArgumentException("File ID must not be negative");
        }
        Objects.requireNonNull(index, "Index must not be null");
        HintFile hint;
        try {
            hint = HintFileReader.read(directory, fileId);
            HintFileValidator.validate(directory, hint);
        } catch (IOException error) {
            hint = null;
        }

        Map<String, EntryLocation> loadedIndex = new HashMap<>(index);
        if (hint != null) {
            for (HintEntry entry : hint.getEntries()) {
                if (entry.isDeleted()) {
                    loadedIndex.remove(entry.getKey());
                } else {
                    loadedIndex.put(entry.getKey(), new EntryLocation(fileId, entry.getOffset()));
                }
            }
        } else {
            try (CaskFile source = CaskFile.openReadOnly(directory, fileId)) {
                source.loadIndex(fileId, loadedIndex);
            }
        }

        index.clear();
        index.putAll(loadedIndex);
        return hint != null;
    }
}
