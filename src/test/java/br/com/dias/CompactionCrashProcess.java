package br.com.dias;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class CompactionCrashProcess {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        String phase = args[1];
        try (WriterLock ignored = new WriterLock(directory)) {
            if (phase.equals("writing")) {
                Path output = Files.createTempDirectory(directory, ".compaction-");
                Files.write(output.resolve("0.cask"), new byte[]{1, 2, 3});
                Runtime.getRuntime().halt(23);
            }

            Map<String, EntryLocation> index;
            try (CaskFile file = CaskFile.openReadOnly(directory, 0)) {
                index = file.loadIndex(0);
            }
            CompactionPlan plan = new CompactionPlan(directory, index);
            Path output = CompactionWriter.write(directory, plan, 40);
            CompactionPreparation.prepare(directory, output, plan, 40);
            if (phase.equals("ready")) {
                Files.writeString(directory.resolve(".current-crash.tmp"), "BITCASK_CURRENT_V1\n");
                Runtime.getRuntime().halt(23);
            }
            if (phase.equals("committed")) {
                CompactionRecovery.commit(directory, output);
                Runtime.getRuntime().halt(23);
            }
            throw new IllegalArgumentException("Unknown crash phase: " + phase);
        }
    }
}
