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

## Technologies

- Java 17 or later
- Maven
- JUnit Jupiter

## Running Tests

From the project directory, run:

    mvn test
