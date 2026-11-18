package fi.alavesa.idcards.scanner;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What a scanner sends to its computers: who, when, where, and the result. */
public record ScanRecord(long at, int scannerId, String scannerName, @Nullable UUID owner, @Nullable UUID holder, boolean granted, String reason) {
}
