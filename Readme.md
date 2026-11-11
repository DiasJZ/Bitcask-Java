# Bitcask in Java

A persistent key-value store implemented in Java, used as an embedded library.
It stores UTF-8 string keys and values on disk and has no runtime dependencies
outside the Java standard library.

## Build

Use JDK 17 or later and Maven. From the project directory, run:

```text
mvn clean verify
```

This compiles the project, runs the tests, and creates
`target/bitcask-1.0-SNAPSHOT.jar`. The JAR contains the library classes; it has no
application entry point and is used on another application's classpath.
JUnit is required only for tests. Generated files in `target/` are not committed.

## Usage

Save this example as `BitcaskExample.java` outside `src/main/java`:

```java
import br.com.dias.Bitcask;

import java.io.IOException;
import java.nio.file.Path;

public class BitcaskExample {
    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args[0]);

        try (Bitcask store = new Bitcask(directory, true)) {
            store.put("name", "Ana");
            store.put("name", "Bia");
            System.out.println(store.get("name").orElseThrow());

            store.put("note", "");
            store.delete("note");
            System.out.println(store.get("note").isPresent());
            store.compact();
        }

        try (Bitcask store = new Bitcask(directory)) {
            System.out.println(store.get("name").orElseThrow());
        }
    }
}
```

Compile and run it on Windows after building the JAR:

```text
javac --release 17 -cp target/bitcask-1.0-SNAPSHOT.jar -d target/example BitcaskExample.java
java -cp "target/bitcask-1.0-SNAPSHOT.jar;target/example" BitcaskExample target/example-data
```

On Linux and macOS, replace the classpath separator `;` with `:`. The example
prints `Bia`, `false`, and `Bia` on separate lines. Its data is stored under
`target/example-data`, which a subsequent `mvn clean` removes. Applications should
choose a persistent directory outside the build output.

`get(key)` returns `Optional.empty()` for a missing or deleted key. Empty keys
and empty values are supported. `put` requires a non-null key and value; use
`delete(key)` to remove a key. Use try-with-resources to close the store and
release its writer lock.

## Design

Records are appended to data files. An in-memory index tracks the
location of the latest value for each key.

The implementation includes reads, writes, updates, deletions, index
reconstruction on startup, CRC32 validation, disk synchronization,
a single-writer lock, file rotation, compaction, and hint files.

## Record Format

CRC32 | timestamp | key size | value size | key | value

| Field | Size |
|---|---|
| CRC32 | 4 bytes |
| Timestamp | 8 bytes |
| Key size | 8 bytes |
| Value size | 8 bytes |
| Key | Variable |
| Value | Variable |

The header occupies 28 bytes. Numbers use big-endian byte order.
Text uses UTF-8, and lengths are measured in bytes.
Deletion records use a value size of -1 and contain no value bytes.
An empty string has a value size of 0 and remains a valid value.
Timestamps represent seconds since the Unix epoch.

CRC32 covers every field after the checksum itself.

## Synchronization and Locking

Call `sync()` to synchronize pending writes with the storage device.
Use `new Bitcask(directory, true)` to synchronize after each `put` and `delete`.
The default constructor synchronizes on `sync()` and `close()`.

Each open Bitcask holds an exclusive operating-system lock on `bitcask.lock`.
A second writer for the same directory is rejected. Closing the store releases
the lock. The lock file remains in the directory and is reused on the next open.

## File Rotation and Recovery

Data files use numeric names such as `0.cask`, `1.cask`, and `2.cask`.
The default file size limit is 10 MiB. Use
`new Bitcask(directory, false, maxFileSize)` to set a limit in bytes.
Writes rotate to the next file before exceeding the limit. A single record
larger than the limit is rejected, including its header and UTF-8 data.

On startup, files are read in numeric order to rebuild the index. Later
records replace earlier values, and deletion records remove earlier keys.
Writing resumes in the highest-numbered writable file, creating one after a
compacted base when necessary, and rotates at the configured size limit.
Corrupt or incomplete records cause opening to fail without modifying data.

## Compaction

Call `compact()` to rewrite only the current values and discard obsolete
records. Operations on the same instance wait until compaction finishes.
The output is validated and synchronized before `CURRENT` atomically selects
the new data directory. Old data files are removed after the switch succeeds.

Always reopen the original database directory. Startup uses `CURRENT` to
select the committed files and ignores uncommitted temporary output.
Compacted base files remain unchanged; subsequent writes use separate files.
Missing or corrupted committed files cause opening to fail instead of falling
back to outdated data. An interruption during cleanup may leave unused old files.
If cleanup fails after the switch, `compact()` reports the error while the
database continues using the committed data.

## Hint Files

The [version 1 hint format](docs/hint-file-format.md) defines per-file metadata,
record locations, deletion markers, and checksums. An internal writer validates
closed data files and publishes complete, synchronized hints atomically. An
internal reader checks hint structure and checksums and returns ordered metadata.
A loader checks the source size and checksum before using a hint, falling back
to a normal data scan when needed. Source errors still propagate without applying
partial index changes. Rotation generates a hint for each sealed file, and
compaction generates hints for all compacted base files, including an empty base.
Hints are removed with the obsolete data files after a successful compaction.
The active writable file does not receive an automatic hint on ordinary close.
Startup uses validated hints for sealed files and compacted base files, replaying
them in numeric file order. The active writable file is always scanned directly.
Missing, unsupported, corrupt, or stale hints fall back to the corresponding data
file without rewriting the hint. Startup also works with older databases that
have no hints. Source checksum validation still reads the data bytes, so hints
do not eliminate data-file I/O or guarantee faster startup.

If hint generation fails during rotation, the triggering write reports the error
before appending its record. The writer has already moved to the next file and
can accept a retry. A hint-generation failure during compaction output writing
aborts that output before switching the database to it.

## Operational Limits

The key index is kept in memory. Hint loading also holds per-file metadata and
a candidate index in memory. Compaction is explicit and blocks other operations
on the same instance until it finishes. It needs additional disk space for the
replacement files before old files can be removed.

Only one Bitcask instance can open a given database directory at a time. Atomic
file moves must be supported by the filesystem for hint publication and
compaction commits. CRC32 detects accidental corruption; it does not provide
authentication. Incomplete or corrupt data records cause opening to fail rather
than being repaired automatically.

Tests include abrupt JVM termination after synchronized writes. They do not
establish guarantees for storage-device power loss. Unsynchronized writes can
be lost if the process or system fails before synchronization.

## Technologies

- Java 17 or later
- Maven
- JUnit Jupiter

## Running Tests

From the project directory, run:

```text
mvn test
```

Tests cover record encoding, checksums, persistence, deletion, locking, rotation,
compaction, hint validation, fallback, and recovery after process termination.
See [hint integration tests](docs/hint-integration-tests.md) for the combined
scenarios and their limits.
