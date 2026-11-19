package fi.alavesa.idcards.presenting;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.luckperms.PrefixFormatter;
import fi.alavesa.idcards.ui.CardCanvas;
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
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The card a presenting player holds out: a real object in the world, with the owner's face, name, prefix, serial
 * and barcode on it.
 *
 * <p>It is a group of display entities standing in front of the player's chest, between the raised hands: one
 * item_display (the blank card, a thin slab) and, on each face of it, text displays for the details, written in the
 * card font and laid out with the same slot positions as the HUD card. The face is the player-head text object.
 * <ul>
 *   <li>The OUTWARD face is for everybody else. The presenter's own client is not told about those entities.</li>
 *   <li>The INWARD face is for the presenter, who looks at the card from behind: only their client knows it. So
 *       nobody ever sees mirrored text through the card.</li>
 * </ul>
 * All entities stand at the player's feet position and carry their place on the card as a transformation, so one
 * teleport per entity per tick moves the whole card; the client smooths it over one tick, and the target leads the
 * player by their last step so the card does not trail behind a walking player. They do not RIDE the player:
 * Bukkit refuses to teleport a player who carries passengers, which would break every other plugin's teleports
 * for as long as somebody presents. Nothing here is saved to disk.
 */
final class CardProp {

    /** Card width in blocks; height follows the art. */
    private static final float WIDTH = 0.6f;
    /** Centre of the card above the feet, and in front of the body. */
    private static final float HEIGHT = 1.22f, FORWARD = 0.62f;
    /** The card_blank model is 12 model pixels wide and 0.25 thick. */
    private static final float MODEL_WIDTH = 12f / 16f, HALF_THICKNESS = 0.125f / 16f;

    private final IdCardsPlugin plugin;
    private final Player player;
    private final float k;                       // blocks per card pixel
    private final List<Entity> outward = new ArrayList<>(), inward = new ArrayList<>();
    private ItemDisplay slab;
    private Location last;

    CardProp(IdCardsPlugin plugin, Player player, CardView view) {
        this.plugin = plugin;
        this.player = player;
        this.k = WIDTH / plugin.layout().cardWidth;
        Location at = base(player.getLocation());
        last = player.getLocation();
        float scale = WIDTH / MODEL_WIDTH;
        slab = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            prepare(d);
            d.setItemStack(Items.model("card_blank"));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(0f, HEIGHT, FORWARD), new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        });
        write(view, at);
    }

    private void prepare(Display d) {
        d.setPersistent(false);
        d.getPersistentDataContainer().set(plugin.keys().helper, PersistentDataType.BYTE, (byte) 1);
        d.setTeleportDuration(1);
        d.setViewRange((float) Math.max(0.1, plugin.settings().presentRenderDistance / 64.0));
    }

    /** Where the group stands for a player location: at the feet, turned with the body, never tilted. */
    private static Location base(Location player) {
        Location l = player.clone();
        l.setPitch(0f);
        return l;
    }

    /** (Re)writes the details on both faces. */
    void write(CardView view, Location at) {
        for (Entity e : outward) e.remove();
        for (Entity e : inward) e.remove();
        outward.clear();
        inward.clear();
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
            prefix.append(text(s, run.color() == null ? paper : TextColor.color(layout.palette[layout.nearest(run.color().value())])));
            prefixWidth += layout.width(s);
        }
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < layout.barcode.maxChars(); i++) bars.append((char) ('' + (int) ((view.barcode() >>> (i * 3)) & 7)));

        for (boolean out : new boolean[]{true, false}) {
            // the head glyph is 8 font pixels wide; the photo slot enlarges it
            line(out, at, Component.object(view.head()).shadowColor(ShadowColor.none()), 8, layout.photo);
            line(out, at, text(name, ink), layout.width(name), nameSlot);
            line(out, at, prefix.build(), prefixWidth, layout.prefix);
            line(out, at, text(view.serial(), ink), layout.width(view.serial()), layout.serial);
            line(out, at, text(bars.toString(), ink), bars.length() * layout.barsAdvance, layout.barcode);
        }
    }

    private static Component text(String s, TextColor color) {
        return Component.text(s, color).font(CardCanvas.FONT).shadowColor(ShadowColor.none());
    }

    /**
     * One text display on one face. A text display draws a font pixel as scale/40 block, centred on the entity and
     * growing upwards from it; the slot gives where the text starts on the card and how much it is enlarged.
     */
    private void line(boolean out, Location at, Component text, int widthFont, Layout.Slot slot) {
        if (widthFont <= 0) return;
        Layout layout = plugin.layout();
        float centreX = slot.x() + widthFont * slot.scale() / 2f, bottom = slot.y() + 8 * slot.scale();
        // card pixels -> the group's frame: +x is the presenter's left (the reader's right on either face), +z forward
        float x = (centreX - layout.cardWidth / 2f) * k, y = HEIGHT + (layout.cardHeight / 2f - bottom) * k;
        float lift = HALF_THICKNESS * (WIDTH / MODEL_WIDTH) + 0.004f;
        float z = out ? FORWARD + lift : -(FORWARD - lift);
        float scale = slot.scale() * k * 40f;
        Location l = at.clone();
        if (!out) l.setYaw(l.getYaw() + 180f);
        TextDisplay d = l.getWorld().spawn(l, TextDisplay.class, t -> {
            prepare(t);
            t.setBillboard(Display.Billboard.FIXED);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setDefaultBackground(false);
            t.setShadowed(false);
            t.setLineWidth(10000);
            t.setTransformation(new Transformation(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(scale), new Quaternionf()));
            t.text(text);
            if (!out) t.setVisibleByDefault(false);
        });
        if (out) {
            player.hideEntity(plugin, d);
            outward.add(d);
        } else {
            player.showEntity(plugin, d);
            inward.add(d);
        }
    }

    /** Follows the player. Called every tick; does nothing while they stand still. */
    void follow() {
        Location now = player.getLocation();
        if (now.getWorld() != last.getWorld()) return; // the presenting state ends on a world change
        boolean moved = now.getX() != last.getX() || now.getY() != last.getY() || now.getZ() != last.getZ() || now.getYaw() != last.getYaw();
        if (!moved) return;
        // lead by the last step: clients show a teleport one tick late
        Vector step = now.toVector().subtract(last.toVector());
        if (step.lengthSquared() > 4) step.zero(); // a jump in position, not a step
        last = now;
        Location target = base(now).add(step);
        slab.teleport(target);
        for (Entity e : outward) e.teleport(target);
        Location back = target.clone();
        back.setYaw(back.getYaw() + 180f);
        for (Entity e : inward) e.teleport(back);
    }

    Location location() {
        return base(player.getLocation());
    }

    boolean valid() {
        if (!slab.isValid()) return false;
        for (Entity e : outward) if (!e.isValid()) return false;
        for (Entity e : inward) if (!e.isValid()) return false;
        return true;
    }

    void remove() {
        slab.remove();
        for (Entity e : outward) e.remove();
        for (Entity e : inward) e.remove();
        outward.clear();
        inward.clear();
    }
}
