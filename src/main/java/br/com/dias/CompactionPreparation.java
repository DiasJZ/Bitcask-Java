package br.com.dias;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

final class CompactionPreparation {
    static Path prepare(Path directory, Path output, CompactionPlan plan, long maxFileSize) throws IOException {
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("Maximum file size must be greater than zero");
        }
        Path root = directory.toRealPath();
        Path candidate = output.toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                || !candidate.getFileName().toString().startsWith(".compaction-")
                || !candidate.toRealPath().getParent().equals(root)) {
            throw new IOException("Compaction output must be a temporary directory inside the database");
        }
        Path ready = candidate.resolve("READY");
        if (Files.exists(ready, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(ready.toString());
        }

        List<Long> fileIds = findFiles(candidate);
        Set<String> copiedKeys = new HashSet<>();
        StringBuilder manifest = new StringBuilder("BITCASK_COMPACTION_V1\n");
        manifest.append(fileIds.size()).append('\n');
        for (long fileId : fileIds) {
            Path path = candidate.resolve(fileId + ".cask");
            long size;
            try (CaskFile file = CaskFile.openReadOnly(candidate, fileId)) {
                size = file.size();
                if (size > maxFileSize || (size == 0 && (fileIds.size() != 1 || !plan.getKeys().isEmpty()))) {
                    throw new IOException("Invalid compacted file size: " + path.getFileName());
                }
                long offset = 0;
                while (offset < size) {
                    Entry entry = file.read(offset);
                    String key = entry.getKey();
                    if (entry.isDeleted() || !plan.getKeys().contains(key) || !copiedKeys.add(key)) {
                        throw new IOException("Unexpected or duplicate compacted key: " + key);
                    }
                    byte[] encoded = entry.encode();
                    if (!Arrays.equals(encoded, plan.readEntry(key).encode())) {
                        throw new IOException("Compacted record does not match the original: " + key);
                    }
                    offset += encoded.length;
                }
                if (offset != size) {
                    throw new IOException("Invalid compacted record boundary");
                }
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            manifest.append(fileId).append(' ').append(size).append(' ').append(checksum(path)).append('\n');
        }
        if (!copiedKeys.equals(plan.getKeys())) {
            throw new IOException("Compacted output is missing live records");
        }
        manifest.append("END\n");
        Path pending = Files.createTempFile(candidate, ".ready-", ".tmp");
        try {
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
            return ready;
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private static List<Long> findFiles(Path directory) throws IOException {
        List<Long> fileIds = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.matches("(0|[1-9][0-9]*)\\.cask")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected compaction output: " + name);
                }
                try {
                    fileIds.add(Long.parseLong(name.substring(0, name.length() - 5)));
                } catch (NumberFormatException error) {
                    throw new IOException("Invalid compacted file ID: " + name, error);
                }
            }
        }
        fileIds.sort(Long::compareTo);
        if (fileIds.isEmpty()) {
            throw new IOException("Compaction output has no data files");
        }
        for (int i = 0; i < fileIds.size(); i++) {
            if (fileIds.get(i) != i) {
                throw new IOException("Compacted file IDs must be consecutive and start at zero");
            }
        }
        return fileIds;
    }

    private static long checksum(Path path) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        try (var input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                crc.update(buffer, 0, count);
            }
        }
        return crc.getValue();
    }
}
