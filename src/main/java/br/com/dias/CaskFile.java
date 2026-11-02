package br.com.dias;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

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
}