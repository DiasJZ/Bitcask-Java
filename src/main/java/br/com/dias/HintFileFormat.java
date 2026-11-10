package br.com.dias;

final class HintFileFormat {
    static final int MAGIC = 0x42484E54;
    static final int VERSION = 1;
    static final int HEADER_SIZE = 2 * Integer.BYTES + 2 * Long.BYTES + Integer.BYTES + Long.BYTES;
    static final int ENTRY_HEADER_SIZE = 4 * Long.BYTES;
    static final int CHECKSUM_SIZE = Integer.BYTES;
    static final int DATA_RECORD_HEADER_SIZE = Integer.BYTES + 3 * Long.BYTES;
    static final long DELETED_VALUE_SIZE = -1L;

    private HintFileFormat() {
    }
}
