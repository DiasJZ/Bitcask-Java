package br.com.dias;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

final class HintFileReader {
    private HintFileReader() {
    }

    static HintFile read(Path directory, long fileId) throws IOException {
        if (fileId < 0) {
            throw new IllegalArgumentException("File ID must not be negative");
        }
        Path path = directory.resolve(fileId + ".hint");
        long fileSize = Files.size(path);
        long remaining = fileSize - HintFileFormat.HEADER_SIZE - HintFileFormat.CHECKSUM_SIZE;
        if (remaining < 0) {
            throw new IOException("Incomplete hint file");
        }

        CRC32 checksum = new CRC32();
        try (DataInputStream input = new DataInputStream(new CheckedInputStream(
                new BufferedInputStream(Files.newInputStream(path)), checksum))) {
            if (input.readInt() != HintFileFormat.MAGIC) {
                throw new IOException("Invalid hint magic");
            }
            if (input.readInt() != HintFileFormat.VERSION) {
                throw new IOException("Unsupported hint version");
            }
            if (input.readLong() != fileId) {
                throw new IOException("Hint file ID does not match its filename");
            }
            long dataSize = input.readLong();
            long dataChecksum = Integer.toUnsignedLong(input.readInt());
            long entryCount = input.readLong();
            if (dataSize < 0 || entryCount < 0 || entryCount > Integer.MAX_VALUE) {
                throw new IOException("Invalid hint size or entry count");
            }
            if (entryCount > remaining / HintFileFormat.ENTRY_HEADER_SIZE
                    || entryCount > dataSize / HintFileFormat.DATA_RECORD_HEADER_SIZE) {
                throw new IOException("Invalid hint size or entry count");
            }

            List<HintEntry> entries = new ArrayList<>();
            long nextOffset = 0;
            for (long i = 0; i < entryCount; i++) {
                if (remaining < HintFileFormat.ENTRY_HEADER_SIZE) {
                    throw new IOException("Incomplete hint entry");
                }
                long timestamp = input.readLong();
                long offset = input.readLong();
                long keySize = input.readLong();
                long valueSize = input.readLong();
                remaining -= HintFileFormat.ENTRY_HEADER_SIZE;
                long valueBytes = valueSize == HintFileFormat.DELETED_VALUE_SIZE ? 0 : valueSize;
                long maxPayload = Integer.MAX_VALUE - HintFileFormat.DATA_RECORD_HEADER_SIZE;
                if (offset != nextOffset || keySize < 0 || valueSize < HintFileFormat.DELETED_VALUE_SIZE) {
                    throw new IOException("Invalid hint entry size or offset");
                }
                if (keySize > remaining || keySize > maxPayload || valueBytes > maxPayload - keySize) {
                    throw new IOException("Invalid hint entry size or offset");
                }
                long recordSize = HintFileFormat.DATA_RECORD_HEADER_SIZE + keySize + valueBytes;
                if (recordSize > dataSize - nextOffset) {
                    throw new IOException("Hint entry exceeds the declared data size");
                }
                byte[] keyBytes = new byte[(int) keySize];
                input.readFully(keyBytes);
                String key = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(keyBytes)).toString();
                entries.add(new HintEntry(timestamp, offset, key, valueSize));
                remaining -= keySize;
                nextOffset += recordSize;
            }

            if (remaining != 0 || nextOffset != dataSize || (dataSize == 0 && dataChecksum != 0)) {
                throw new IOException("Hint entries do not match the declared sizes");
            }
            long calculatedChecksum = checksum.getValue();
            long storedChecksum = Integer.toUnsignedLong(input.readInt());
            if (calculatedChecksum != storedChecksum) {
                throw new IOException("Invalid hint checksum");
            }
            if (input.read() != -1) {
                throw new IOException("Unexpected bytes after hint checksum");
            }
            return new HintFile(fileId, dataSize, dataChecksum, entries);
        }
    }
}
