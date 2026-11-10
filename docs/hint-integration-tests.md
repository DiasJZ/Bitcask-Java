# Hint Integration Tests

Run the complete suite with `mvn test`, or these scenarios with
`mvn -Dtest=BitcaskHintIntegrationTest test`.

The integration tests exercise the public `Bitcask` API with actual temporary
files and a separate JVM for process-exit scenarios.

- A deterministic sequence of writes and deletions is checked against a reference
  map, with automatic synchronization both enabled and disabled. Repeated
  compaction and reopening combine valid, missing, and damaged hints. Reopening
  again after removing all hints must return the same values without changing
  existing data bytes.
- Large UTF-8 values cross the checksum streaming buffer boundary. Updates,
  deletions, empty keys, and empty values survive rotation, hint fallback,
  compaction, and reopening.
- A child JVM synchronizes its writes and halts without normal resource cleanup.
  One scenario leaves a deliberately constructed partial temporary hint with no
  published counterpart; another leaves a completed published hint. Reopening
  must recover the values and deletions, acquire the released writer lock, and
  allow further writing and compaction.

The process tests simulate interrupted application state. They do not inject a
failure inside the atomic rename itself or simulate storage-device power loss.
These checks establish behavior for the covered cases, not performance claims.
