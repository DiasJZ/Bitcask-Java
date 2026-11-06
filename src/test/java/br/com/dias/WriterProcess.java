package br.com.dias;

import java.io.IOException;
import java.nio.file.Path;

public class WriterProcess {
    public static void main(String[] args) throws IOException {
        try (Bitcask store = new Bitcask(Path.of(args[0]), true)) {
            store.put("process", "ok");
        } catch (IOException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        }
    }
}
