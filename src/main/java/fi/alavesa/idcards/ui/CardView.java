package fi.alavesa.idcards.ui;

import fi.alavesa.idcards.luckperms.PrefixFormatter;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;

/** Everything a card shows, already looked up. Rendering it needs nothing else. */
public record CardView(UUID owner, String name, List<PrefixFormatter.Run> prefix, String serial, long barcode,
                       PlayerHeadObjectContents head) {

    /** XXXX-XXXX-XXXX from the UUID: stable, and different for every owner. */
    public static String serial(UUID owner) {
        String hex = String.format("%012X", (owner.getMostSignificantBits() ^ Long.rotateLeft(owner.getLeastSignificantBits(), 17)) & 0xFFFFFFFFFFFFL);
        return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12);
    }

    public static long barcode(UUID owner) {
        return owner.getLeastSignificantBits() ^ (owner.getMostSignificantBits() * 0x9E3779B97F4A7C15L);
    }
}
