package br.com.dias;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32;

final class CompactionRecovery {
    static CompactionState recover(Path directory) throws IOException {
        Path root = directory.toRealPath();
        Path current = root.resolve("CURRENT");
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            return new CompactionState(root, 0);
        }
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) || Files.size(current) > 4096) {
            throw new IOException("Invalid CURRENT file");
        }
        List<String> lines = Files.readAllLines(current, StandardCharsets.UTF_8);
        if (lines.size() != 5 || !lines.get(0).equals("BITCASK_CURRENT_V1") || !lines.get(4).equals("END")) {
            throw new IOException("Invalid CURRENT format");
        }
        String body = String.join("\n", lines.subList(0, 3)) + "\n";
        if (checksum(body) != parseChecksum(lines.get(3))) {
            throw new IOException("Invalid CURRENT checksum");
        }
        String name = lines.get(1);
        if (!name.matches("\\.compaction-[A-Za-z0-9-]+")) {
            throw new IOException("Invalid compaction directory name");
        }
        Path output = validateDirectory(root, root.resolve(name));
        long readyChecksum = parseChecksum(lines.get(2));
        int fileCount = validateReady(output, readyChecksum);
        return new CompactionState(output, fileCount);
    }

    static void commit(Path directory, Path output) throws IOException {
        Path root = directory.toRealPath();
        recover(root);
        Path candidate = validateDirectory(root, output);
        Path ready = candidate.resolve("READY");
        if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Compaction output is not ready");
        }
        long readyChecksum = CompactionPreparation.checksum(ready);
        int fileCount = validateReady(candidate, readyChecksum);
        validatePreparedContents(candidate, fileCount);

        String body = "BITCASK_CURRENT_V1\n" + candidate.getFileName() + "\n" + readyChecksum + "\n";
        byte[] bytes = (body + checksum(body) + "\nEND\n").getBytes(StandardCharsets.UTF_8);
        Path pending = Files.createTempFile(root, ".current-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(pending, root.resolve("CURRENT"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private static Path validateDirectory(Path root, Path output) throws IOException {
        Path candidate = output.toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                || candidate.getFileName() == null
                || !candidate.getFileName().toString().matches("\\.compaction-[A-Za-z0-9-]+")) {
            throw new IOException("Invalid compaction directory");
        }
        Path real = candidate.toRealPath();
        if (!root.equals(real.getParent())) {
            throw new IOException("Compaction directory must be inside the database");
        }
        return real;
    }

    private static int validateReady(Path directory, long expectedChecksum) throws IOException {
        Path ready = directory.resolve("READY");
        if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS)
                || CompactionPreparation.checksum(ready) != expectedChecksum) {
            throw new IOException("Missing or corrupted READY manifest");
        }
        List<String> lines = Files.readAllLines(ready, StandardCharsets.UTF_8);
        if (lines.size() < 4 || !lines.get(0).equals("BITCASK_COMPACTION_V1")
                || !lines.get(lines.size() - 1).equals("END")) {
            throw new IOException("Invalid READY format");
        }
        long declaredCount = parseNumber(lines.get(1));
        int fileCount = lines.size() - 3;
        if (declaredCount != fileCount) {
            throw new IOException("Invalid READY file count");
        }
        for (int i = 0; i < fileCount; i++) {
            String[] fields = lines.get(i + 2).split(" ", -1);
            if (fields.length != 3 || parseNumber(fields[0]) != i) {
                throw new IOException("Invalid READY file entry");
            }
            long size = parseNumber(fields[1]);
            long crc = parseChecksum(fields[2]);
            Path file = directory.resolve(i + ".cask");
            if ((size == 0 && fileCount != 1)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) != size || CompactionPreparation.checksum(file) != crc) {
                throw new IOException("Missing or corrupted compacted file: " + file.getFileName());
            }
        }
        return fileCount;
    }

    private static void validatePreparedContents(Path directory, int fileCount) throws IOException {
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (name.equals("READY")) {
                    continue;
                }
                if (!name.matches("(0|[1-9][0-9]*)\\.cask")
                        || parseNumber(name.substring(0, name.length() - 5)) >= fileCount) {
                    throw new IOException("Unexpected file in prepared compaction: " + name);
                }
            }
        }
    }

    private static long parseNumber(String text) throws IOException {
        if (!text.matches("0|[1-9][0-9]*")) {
            throw new IOException("Invalid compaction metadata number");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException error) {
            throw new IOException("Compaction metadata number is too large", error);
        }
    }

    private static long parseChecksum(String text) throws IOException {
        long value = parseNumber(text);
        if (value > 0xffffffffL) {
            throw new IOException("Invalid compaction metadata checksum");
        }
        return value;
    }

    private static long checksum(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
