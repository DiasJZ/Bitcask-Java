package br.com.dias;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class WriterLock implements AutoCloseable {
    private final FileChannel channel;

    WriterLock(Path directory) throws IOException {
        Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve("bitcask.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() == null) {
                throw new IOException("Bitcask is already open for writing");
            }
        } catch (IOException | RuntimeException error) {
            try {
                channel.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            if (error instanceof OverlappingFileLockException) {
                throw new IOException("Bitcask is already open for writing", error);
            }
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
