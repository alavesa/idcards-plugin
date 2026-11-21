package fi.alavesa.idcards.presenting;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.luckperms.PrefixFormatter;
import fi.alavesa.idcards.ui.CardView;
import fi.alavesa.idcards.ui.Layout;
import fi.alavesa.idcards.util.Items;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The card as an object: what a player holding a card sees in front of them, and what others see when it is held out.
 *
 * <p>It is made of display entities: an item_display (a thin slab with the bare card on its front and the back design
 * on its back) and text displays for face, name, prefix, serial and barcode, written in the card font at the slot
 * positions of layout.properties. The face is the player-head text object, so it is the owner's real skin.
 * There are two groups:
 * <ul>
 *   <li><b>The holder's own card</b> exists while the card is the held item and only the holder's client knows it.
 *       Its entities are billboarded CENTER: the client turns them with the holder's view every frame, with no
 *       server round trip (the same technique as the Firearms viewmodel). So the card stays at the same place on
 *       the screen however fast the holder turns or looks up and down. While the card is held out, this slab turns
 *       its back to the holder and moves forward, and the details are hidden.</li>
 *   <li><b>The hologram for everyone else</b> exists only while the card is held out (right mouse button down):
 *       the slab with its front away from the holder, the details on it, turned with the holder's body by the
 *       server. The holder's client is not told about it.</li>
 * </ul>
 * Both groups are attached to the player as client-side passengers ({@link fi.alavesa.idcards.nms.RideBridge}), so
 * they move with the player without trailing; on the server they are ordinary, unsaved entities that are put back
 * near the player now and then so they stay tracked. Without the bridge they are teleported every tick instead.
 */
final class CardProp {

    /** Where the card's centre is, relative to the top of the player's hit box (where passengers sit), and how wide it is. */
    private record Pose(float right, float up, float forward, float width) {
    }

    private static final Pose HELD = new Pose(0.24f, -0.30f, 0.60f, 0.36f), SHOWN = new Pose(0f, -0.62f, 0.62f, 0.5f);
    /** The card model is 12 model pixels wide and 0.25 thick. */
    private static final float MODEL_WIDTH = 12f / 16f, MODEL_THICKNESS = 0.25f / 16f;
    private static final int TURN_TICKS = 4;

    private final IdCardsPlugin plugin;
    private final Player player;
    private CardView view;
    private final ItemDisplay slab;
    private final List<TextDisplay> mine = new ArrayList<>();
    private final List<Entity> theirs = new ArrayList<>();
    private boolean shown;
    private float lastYaw;
    private int age;

    CardProp(IdCardsPlugin plugin, Player player, CardView view) {
        this.plugin = plugin;
        this.player = player;
        this.view = view;
        Location at = anchor();
        slab = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            prepare(d, true);
            d.setItemStack(Items.model("card_blank"));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(slabTransform(HELD, false, true));
        });
        player.showEntity(plugin, slab);
        writeMine();
        lastYaw = at.getYaw();
    }

    /** Server-side position of all parts: the top of the hit box, where a passenger of the player sits. */
    private Location anchor() {
        Location l = player.getLocation();
        l.add(0, player.getHeight(), 0);
        l.setPitch(0f);
        return l;
    }

    private void prepare(Display d, boolean holderOnly) {
        d.setPersistent(false);
        d.getPersistentDataContainer().set(plugin.keys().helper, PersistentDataType.BYTE, (byte) 1);
        d.setViewRange((float) Math.max(0.1, plugin.settings().presentRenderDistance / 64.0));
        d.setTeleportDuration(plugin.rides().available() ? 0 : 1);
        if (holderOnly) {
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
        }
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Frames. Holder's group (billboard): +x right, +y up, +z towards the holder's camera, so "forward" is -z and the
     * side of the slab the holder sees faces +z. Hologram (fixed, yawed like the body): +x the holder's left, +z
     * forward. An item_display shows a model turned half a turn: the model's front (north) faces the entity's +z.
     */
    private Transformation slabTransform(Pose pose, boolean backToViewer, boolean holderFrame) {
        float scale = pose.width / MODEL_WIDTH;
        Vector3f t = new Vector3f(holderFrame ? pose.right : 0f, pose.up, holderFrame ? -pose.forward : pose.forward);
        Quaternionf turn = new Quaternionf().rotateY(backToViewer ? (float) Math.PI : 0f);
        return new Transformation(t, turn, new Vector3f(scale), new Quaternionf());
    }

    /** One detail on the card: where its text display goes for a pose. Text is centred on the entity and grows upwards. */
    private Transformation textTransform(Pose pose, Layout.Slot slot, int widthFont, boolean holderFrame) {
        Layout layout = plugin.layout();
        float k = pose.width / layout.cardWidth;                               // blocks per card pixel
        float centreX = slot.x() + widthFont * slot.scale() / 2f, bottom = slot.y() + 8 * slot.scale();
        float x = (centreX - layout.cardWidth / 2f) * k, y = pose.up + (layout.cardHeight / 2f - bottom) * k;
        float lift = MODEL_THICKNESS / 2f * (pose.width / MODEL_WIDTH) + 0.003f;
        // both readers see card x grow to their right: +x in the holder's frame, and +x (the holder's left) in the hologram's
        float z = holderFrame ? -pose.forward + lift : pose.forward + lift;
        return new Transformation(new Vector3f((holderFrame ? pose.right : 0f) + x, y, z), new Quaternionf(), new Vector3f(slot.scale() * k * 40f), new Quaternionf());
    }

    private record Detail(Component text, int widthFont, Layout.Slot slot) {
    }

    private List<Detail> details() {
        Layout layout = plugin.layout();
        TextColor ink = TextColor.color(layout.palette[layout.ink]), paper = TextColor.color(layout.palette[layout.paper]);
        String name = Layout.sanitize(view.name());
        Layout.Slot nameSlot = layout.width(name) <= layout.room(layout.nameBig) ? layout.nameBig : layout.nameSmall;
        name = layout.fit(name, layout.room(nameSlot));
        TextComponent.Builder prefix = Component.text();
        int room = layout.room(layout.prefix), prefixWidth = 0;
        for (PrefixFormatter.Run run : view.prefix()) {
            String s = layout.fit(Layout.sanitize(run.text()), room - prefixWidth);
            if (s.isEmpty()) continue;
            prefix.append(text(s, run.color() == null ? paper : run.color()));
            prefixWidth += layout.width(s);
        }
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < layout.barcode.maxChars(); i++) bars.append((char) ('' + (int) ((view.barcode() >>> (i * 3)) & 7)));
        List<Detail> out = new ArrayList<>();
        out.add(new Detail(Component.object(view.head()).shadowColor(ShadowColor.none()), 8, layout.photo)); // the head glyph is 8 font pixels
        out.add(new Detail(text(name, ink), layout.width(name), nameSlot));
        if (prefixWidth > 0) out.add(new Detail(prefix.build(), prefixWidth, layout.prefix));
        out.add(new Detail(text(view.serial(), ink), layout.width(view.serial()), layout.serial));
        out.add(new Detail(text(bars.toString(), ink), bars.length() * layout.barsAdvance, layout.barcode));
        return out;
    }

    private static Component text(String s, TextColor color) {
        return Component.text(s, color).font(Layout.FONT).shadowColor(ShadowColor.none());
    }

    private TextDisplay line(Location at, Detail d, Pose pose, boolean holderFrame) {
        return at.getWorld().spawn(at, TextDisplay.class, t -> {
            prepare(t, holderFrame);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setDefaultBackground(false);
            t.setShadowed(false);
            t.setLineWidth(10000);
            t.setTransformation(textTransform(pose, d.slot, d.widthFont, holderFrame));
            t.text(d.text);
        });
    }

    // ------------------------------------------------------------------ the two groups

    private void writeMine() {
        mine.forEach(Entity::remove);
        mine.clear();
        if (shown) return; // held out: the holder looks at the back
        Location at = anchor();
        for (Detail d : details()) {
            TextDisplay t = line(at, d, HELD, true);
            player.showEntity(plugin, t);
            mine.add(t);
        }
    }

    private void writeTheirs() {
        theirs.forEach(Entity::remove);
        theirs.clear();
        if (!shown) return;
        Location at = anchor();
        ItemDisplay out = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            prepare(d, false);
            d.setItemStack(Items.model("card_blank"));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(slabTransform(SHOWN, false, false));
        });
        theirs.add(out);
        for (Detail d : details()) theirs.add(line(at, d, SHOWN, false));
        for (Entity e : theirs) player.hideEntity(plugin, e); // the holder has their own slab, seen from behind
    }

    /** Held out (true) or taken back (false). The holder's slab turns and slides over a few ticks. */
    void show(boolean on) {
        if (shown == on) return;
        shown = on;
        slab.setInterpolationDelay(0);
        slab.setInterpolationDuration(TURN_TICKS);
        slab.setTransformation(slabTransform(on ? SHOWN : HELD, on, true));
        writeMine();
        writeTheirs();
        age = 0; // attach the new entities at once
    }

    /** A name, skin or prefix changed. */
    void rewrite(CardView view) {
        this.view = view;
        writeMine();
        writeTheirs();
        age = 0;
    }

    /** Every tick. */
    void tick() {
        Location at = anchor();
        if (plugin.rides().available()) {
            // attached on the clients; the server only turns the hologram with the body and keeps everything tracked
            if (at.getYaw() != lastYaw) for (Entity e : theirs) e.setRotation(at.getYaw(), 0f);
            if (age % 10 == 1) attach();
            if (age % 20 == 0 && slab.getLocation().distanceSquared(at) > 4) {
                move(at);
                age = 0; // a teleport can detach a rider on the client: attach again next tick
            }
        } else if (at.getYaw() != lastYaw || slab.getLocation().distanceSquared(at) > 1.0e-6) {
            move(at);
        }
        lastYaw = at.getYaw();
        age++;
    }

    private void move(Location at) {
        slab.teleport(at);
        for (Entity e : mine) e.teleport(at);
        for (Entity e : theirs) e.teleport(at);
    }

    /** Tells the clients what rides the player: the holder gets their own group, everyone near gets the hologram. */
    private void attach() {
        List<Entity> own = new ArrayList<>(mine);
        own.add(slab);
        plugin.rides().ride(player, player, own);
        if (theirs.isEmpty()) return;
        double r = plugin.settings().presentRenderDistance;
        for (Player viewer : player.getWorld().getNearbyPlayers(player.getLocation(), r)) {
            if (!viewer.equals(player) && viewer.canSee(player)) plugin.rides().ride(viewer, player, theirs);
        }
    }

    boolean valid() {
        if (!slab.isValid()) return false;
        for (Entity e : mine) if (!e.isValid()) return false;
        for (Entity e : theirs) if (!e.isValid()) return false;
        return true;
    }

    void remove() {
        slab.remove();
        mine.forEach(Entity::remove);
        theirs.forEach(Entity::remove);
        mine.clear();
        theirs.clear();
        if (plugin.rides().available() && player.isOnline()) plugin.rides().ride(player, player, List.of()); // the real list again
    }
}
