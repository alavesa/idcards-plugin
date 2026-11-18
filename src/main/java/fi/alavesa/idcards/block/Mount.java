package fi.alavesa.idcards.block;

/** How a placed block sits in its cell. */
public enum Mount {
    /** Standing on the block below; the front faces {@code facing}. */
    FLOOR,
    /** Hanging on the block behind it; the front faces away from that block. */
    WALL
}
