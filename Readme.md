# Bitcask in Java

A persistent key-value store implemented in Java.

## Design

Records are appended to data files. An in-memory index tracks the
location of the latest value for each key.

The project scope includes reads, writes, updates, deletions, index
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
Writing resumes in the highest-numbered file and rotates when necessary.
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
closed data files and publishes complete, synchronized hints atomically. Automatic
generation and startup integration are not enabled yet; the database still scans
its data files.

## Technologies

- Java 17 or later
- Maven
- JUnit Jupiter

## Running Tests

From the project directory, run:

    mvn test
