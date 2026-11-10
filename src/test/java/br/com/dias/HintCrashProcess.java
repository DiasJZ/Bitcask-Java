package br.com.dias;

import java.nio.file.Files;
import java.nio.file.Path;

public class HintCrashProcess {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        String phase = args[1];
        try (Bitcask store = new Bitcask(directory, true, 40)) {
            store.put("name", "Ana");
            store.put("obsolete", "old");
            store.put("name", "Bia");
            store.delete("obsolete");
            store.put("note", "");
            store.sync();
            if (phase.equals("temporary")) {
                Files.delete(directory.resolve("3.hint"));
                Files.write(directory.resolve(".hint-3-interrupted.tmp"), new byte[]{0x42, 0x48});
            } else if (phase.equals("published")) {
                HintFileWriter.write(directory, 3);
            } else {
                throw new IllegalArgumentException("Unknown hint crash phase: " + phase);
            }
            Runtime.getRuntime().halt(23);
        }
    }
}
