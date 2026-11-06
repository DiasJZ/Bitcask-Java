package br.com.dias;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Arrays;

import java.util.HashMap;
import java.util.Map;

public class CaskFile implements AutoCloseable {

    private final RandomAccessFile file;

    public CaskFile(Path directory, long fileId) throws IOException {
        this(pathFor(directory, fileId));
    }

    private static Path pathFor(Path directory, long fileId) {
        if (fileId < 0) {
            throw new IllegalArgumentException("File ID must not be negative");
        }
        return directory.resolve(fileId + ".cask");
    }

    public CaskFile(Path path) throws IOException {
        this(path, false);
    }

    private CaskFile(Path path, boolean readOnly) throws IOException {
        Path absolutePath = path.toAbsolutePath();

        if (!readOnly) {
            Files.createDirectories(absolutePath.getParent());
        }

        this.file = new RandomAccessFile(absolutePath.toFile(), readOnly ? "r" : "rw");
    }

    static Entry readAt(Path directory, EntryLocation location) throws IOException {
        Path path = pathFor(directory, location.getFileId());
        try (CaskFile source = new CaskFile(path, true)) {
            return source.read(location.getOffset());
        }
    }

    public long append(Entry entry) throws IOException {
        return append(entry.encode());
    }

    long append(byte[] record) throws IOException {
        long offset = file.length();

        file.seek(offset);
        file.write(record);

        return offset;
    }

    public long size() throws IOException {
        return file.length();
    }

    Map<String, EntryLocation> loadIndex(long fileId) throws IOException {
        if (fileId < 0) {
            throw new IllegalArgumentException("File ID must not be negative");
        }
        Map<String, EntryLocation> index = new HashMap<>();
        long offset = 0;
        long fileSize = file.length();

        while (offset < fileSize) {
            Entry entry = read(offset);
            if (entry.isDeleted()) {
                index.remove(entry.getKey());
            } else {
                index.put(entry.getKey(), new EntryLocation(fileId, offset));
            }
            offset = file.getFilePointer();
        }

        return index;
    }

    public void sync() throws IOException {
        file.getFD().sync();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    public Entry read(long offset) throws IOException {
        file.seek(offset);

        int headerSize = Integer.BYTES + 3 * Long.BYTES;
        byte[] header = new byte[headerSize];
        file.readFully(header);
        ByteBuffer headerBuffer = ByteBuffer.wrap(header);
        headerBuffer.position(Integer.BYTES + Long.BYTES);

        long keySize = headerBuffer.getLong();
        long valueSize = headerBuffer.getLong();
        long valueBytesSize = valueSize == -1 ? 0 : valueSize;
        long bytesAvailable = file.length() - file.getFilePointer();
        long maxDataSize = Math.min(bytesAvailable, Integer.MAX_VALUE - headerSize);

        if (keySize < 0 || valueSize < -1 || keySize > maxDataSize || valueBytesSize > maxDataSize - keySize) {
            throw new IOException("Invalid record size");
        }

        int dataSize = (int) (keySize + valueBytesSize);
        byte[] record = Arrays.copyOf(header, headerSize + dataSize);
        file.readFully(record, headerSize, dataSize);

        return Entry.decode(record);
    }
}
