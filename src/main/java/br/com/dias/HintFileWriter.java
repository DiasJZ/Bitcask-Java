package br.com.dias;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

final class HintFileWriter {
    private HintFileWriter() {
    }

    static Path write(Path directory, long fileId) throws IOException {
        if (fileId < 0) {
            throw new IllegalArgumentException("File ID must not be negative");
        }
        Path dataPath = directory.resolve(fileId + ".cask");
        Path target = directory.resolve(fileId + ".hint");
        Path temporary = null;

        try {
            try (RandomAccessFile source = new RandomAccessFile(dataPath.toFile(), "r")) {
                temporary = Files.createTempFile(directory, ".hint-" + fileId + "-", ".tmp");
                try (RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
                    long dataSize = source.length();
                    CRC32 dataChecksum = new CRC32();
                    output.seek(HintFileFormat.HEADER_SIZE);
                    long entryCount = writeEntries(source, output, dataSize, dataChecksum);
                    if (source.length() != dataSize) {
                        throw new IOException("Data file changed during hint generation");
                    }
                    output.seek(0);
                    output.writeInt(HintFileFormat.MAGIC);
                    output.writeInt(HintFileFormat.VERSION);
                    output.writeLong(fileId);
                    output.writeLong(dataSize);
                    output.writeInt((int) dataChecksum.getValue());
                    output.writeLong(entryCount);
                }
            }

            CRC32 hintChecksum = new CRC32();
            try (var input = Files.newInputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    hintChecksum.update(buffer, 0, count);
                }
            }
            try (RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
                output.seek(output.length());
                output.writeInt((int) hintChecksum.getValue());
                output.getFD().sync();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException | RuntimeException error) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw error;
        }
    }

    private static long writeEntries(RandomAccessFile source, RandomAccessFile output,
                                     long dataSize, CRC32 dataChecksum) throws IOException {
        byte[] header = new byte[HintFileFormat.DATA_RECORD_HEADER_SIZE];
        byte[] buffer = new byte[8192];
        long entryCount = 0;

        while (source.getFilePointer() < dataSize) {
            long offset = source.getFilePointer();
            if (dataSize - offset < header.length) {
                throw new IOException("Incomplete record header");
            }
            source.readFully(header);
            ByteBuffer fields = ByteBuffer.wrap(header);
            long storedChecksum = Integer.toUnsignedLong(fields.getInt());
            long timestamp = fields.getLong();
            long keySize = fields.getLong();
            long valueSize = fields.getLong();
            long valueBytes = valueSize == HintFileFormat.DELETED_VALUE_SIZE ? 0 : valueSize;
            long available = Math.min(dataSize - source.getFilePointer(), Integer.MAX_VALUE - header.length);
            if (keySize < 0 || valueSize < HintFileFormat.DELETED_VALUE_SIZE
                    || keySize > available || valueBytes > available - keySize) {
                throw new IOException("Invalid record size");
            }

            CRC32 recordChecksum = new CRC32();
            recordChecksum.update(header, Integer.BYTES, header.length - Integer.BYTES);
            dataChecksum.update(header);
            byte[] key = new byte[(int) keySize];
            source.readFully(key);
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(key));
            recordChecksum.update(key);
            dataChecksum.update(key);

            while (valueBytes > 0) {
                int count = (int) Math.min(buffer.length, valueBytes);
                source.readFully(buffer, 0, count);
                recordChecksum.update(buffer, 0, count);
                dataChecksum.update(buffer, 0, count);
                valueBytes -= count;
            }
            if (recordChecksum.getValue() != storedChecksum) {
                throw new IOException("Invalid record checksum");
            }

            output.writeLong(timestamp);
            output.writeLong(offset);
            output.writeLong(keySize);
            output.writeLong(valueSize);
            output.write(key);
            entryCount++;
        }
        return entryCount;
    }
}
