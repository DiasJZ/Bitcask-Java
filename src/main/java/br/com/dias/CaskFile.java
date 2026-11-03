package br.com.dias;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class CaskFile implements AutoCloseable {

    private final RandomAccessFile file;

    public CaskFile(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath();

        Files.createDirectories(absolutePath.getParent());

        this.file = new RandomAccessFile(absolutePath.toFile(), "rw");
    }

    public long append(Entry entry) throws IOException {
        byte[] record = entry.encode();

        long offset = file.length();

        file.seek(offset);
        file.write(record);

        return offset;
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
        long bytesAvailable = file.length() - file.getFilePointer();
        long maxDataSize = Math.min(bytesAvailable, Integer.MAX_VALUE - headerSize);

        if (keySize < 0 || valueSize < 0 || keySize > maxDataSize || valueSize > maxDataSize - keySize) {
            throw new IOException("Invalid record size");
        }

        int dataSize = (int) (keySize + valueSize);
        byte[] record = Arrays.copyOf(header, headerSize + dataSize);
        file.readFully(record, headerSize, dataSize);

        return Entry.decode(record);
    }
}