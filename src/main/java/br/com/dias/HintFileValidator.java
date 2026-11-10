package br.com.dias;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

final class HintFileValidator {
    private HintFileValidator() {
    }

    static void validate(Path directory, HintFile hint) throws IOException {
        Path source = directory.resolve(hint.getFileId() + ".cask");
        long expectedSize = hint.getDataSize();
        if (Files.size(source) != expectedSize) {
            throw new IOException("Hint data size does not match the source file");
        }

        CRC32 checksum = new CRC32();
        long total = 0;
        try (var input = Files.newInputStream(source)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > expectedSize - total) {
                    throw new IOException("Source file grew during hint validation");
                }
                checksum.update(buffer, 0, count);
                total += count;
            }
        }
        if (total != expectedSize || checksum.getValue() != hint.getDataChecksum()) {
            throw new IOException("Hint data checksum or size does not match the source file");
        }
    }
}
