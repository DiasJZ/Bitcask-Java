# Hint File Format

This document defines version 1. Hint writing, reading, validation, and startup
integration are implemented in separate steps. Defining this format does not
enable hints in the database.

`HintFileWriter.write(directory, fileId)` implements generation and atomic
publication. Its caller must keep the source file unchanged for the entire call.
It validates record sizes, record CRC32 values, and UTF-8 keys, and streams value
bytes without storing them in the hint. It fails if atomic publication is not
supported; there is no non-atomic replacement fallback. Automatic generation and
hint loading are not connected to database operations yet.

`HintFileReader.read(directory, fileId)` reads a hint into an immutable
`HintFile`, preserving every `HintEntry` in physical record order. It checks the
hint structure, UTF-8 keys, record boundaries, and hint checksum before returning
the result. It does not open the source `.cask` file or update the database index.
The declared source size and checksum are metadata at this stage; checking them
against the actual source and falling back to a data scan are separate steps.
The reader keeps all hint entries in memory and supports at most
`Integer.MAX_VALUE` entries, subject to available memory.

## Purpose and Location

A hint file describes every record in one data file without copying its values.
For example, `7.hint` belongs to `7.cask` in the same data directory, including
when that directory is a committed compaction generation.

Hints are derived data. The `.cask` file remains the source of truth. Only files
that are no longer receiving writes are eligible for hint generation and use.
The active writable file is scanned normally.

## Layout

`header | entries in data-file order | hint CRC32`

Numbers use big-endian byte order. Lengths and offsets are measured in bytes.
Keys use UTF-8. Integer and long fields occupy 4 and 8 bytes respectively.
CRC32 fields store unsigned 32-bit values in 4 bytes.

### Header: 36 Bytes

| Field | Size | Meaning |
|---|---|---|
| Magic | 4 bytes | `0x42484E54`, the ASCII bytes `BHNT` |
| Version | 4 bytes | `1` |
| File ID | 8 bytes | Nonnegative ID matching the hint and data filenames |
| Data file size | 8 bytes | Exact size of the source `.cask` file |
| Data CRC32 | 4 bytes | CRC32 over every byte of the source `.cask` file |
| Entry count | 8 bytes | Number of records in the source file, including deletions |

### Each Entry: 32 Fixed Bytes Plus the Key

| Field | Size | Meaning |
|---|---|---|
| Timestamp | 8 bytes | Original record timestamp, in seconds since the Unix epoch |
| Record offset | 8 bytes | Position of the record's CRC32 field in the `.cask` file |
| Key size | 8 bytes | Number of UTF-8 bytes in the key |
| Value size | 8 bytes | Original value length in bytes, or `-1` for deletion |
| Key | Variable | Exactly `key size` bytes |

There are no value bytes in a hint entry. An empty value has size `0`; it is
different from a deletion. Empty keys are valid. Timestamps are copied unchanged
and do not determine replay order.

An entry exists for every source record, even when a later record updates or
deletes the same key. A reader must replay all entries in order. A deletion must
remove the key from the shared index, including a value from an earlier file.

### Trailer: 4 Bytes

The final field is a CRC32 over the entire hint header and all entries, excluding
the trailer itself. No bytes may follow it.

An empty data file has size `0`, data CRC32 `0`, and entry count `0`. Its hint
contains only the header and trailer, for a total of 40 bytes.

## Validation Rules

- Accept only the specified magic and supported version.
- Require a matching file ID, data file size, and data CRC32.
- Verify the hint CRC32 before applying any entries to the shared index.
- Reject negative counts, offsets, or key sizes, and value sizes below `-1`.
- Require valid UTF-8 keys and lengths that fit the available hint bytes.
- Bound the entry count by both the hint size and the source data file size.
- The first record offset must be `0`. Each next offset must equal the previous
  offset plus `28 + key size + max(value size, 0)`.
- The final source record must end exactly at the declared data file size.
- Check arithmetic overflow and enforce the data record size supported by the
  existing `CaskFile` reader before allocating buffers.
- Read exactly the declared entry count and then the trailer.

The source fingerprint covers content, so an equal-length source replacement is
not accepted based only on its size or modification time. CRC32 detects accidental
corruption; it is not a cryptographic authenticity check.

Version 1 verifies the data CRC32 by streaming source bytes. This still reads the
data file, but does not need to decode its values to rebuild the index. It does
not promise to eliminate source-file I/O or guarantee faster startup.

## Publication and Fallback

The writer must validate source records, write a temporary hint beside the data
file, complete its header and checksum, synchronize it, close it, and atomically
publish it as `<fileId>.hint`. A failed generation must not replace an existing
hint with a partial file. Temporary hint files are never used during startup.

If a hint is missing, unsupported, corrupt, or stale, discard its candidate index
changes and scan the corresponding `.cask` file normally. Errors in that data
file remain errors; a hint must not hide them. Do not merge partially validated
hints into the database index.

Replay files in numeric ID order and records in offset order, regardless of
whether each file is loaded from hints or directly from data. Hints must not
change lookup, update, deletion, rotation, or compaction behavior.
