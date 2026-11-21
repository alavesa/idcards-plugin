package fi.alavesa.idcards.card;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.config.Lang;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.KineticWeapon;
import io.papermc.paper.datacomponent.item.UseEffects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Makes and reads ID cards.
 *
 * <p>A card is a spear item with the pack's card model. A spear, because vanilla gives spears a hold-out animation
 * while the right mouse button is held, which is how a card is presented; everything that makes a spear a weapon is
 * taken off it (no damage, no knockback, no dismounting, no durability, no slowdown). What makes the item a card,
 * and whose, is its persistent data: owner UUID, the card's own id, the issue time and an HMAC over those three made
 * with a secret that never leaves this server (plugins/IdCards/secret.key). Name and lore are decoration: renaming a
 * card on an anvil changes nothing, and a card whose data was typed in by hand (creative mode can write any item
 * data) has no valid signature and reads as FORGED. The owner's name is never stored on the card; it is looked up
 * from the UUID.
 *
 * <p>With card.one-per-player the newest card of an owner is the only valid one (cards table); older ones read as
 * REVOKED, so "one card" holds even when an old card is still lying in a chest.
 */
public final class CardService {

    public static final String TYPE = "card";
    public static final Key MODEL = Key.key("idcards", "id_card");
    public static final Material BASE = Material.WOODEN_SPEAR;

    private final IdCardsPlugin plugin;
    private final byte[] secret;
    private final Map<UUID, UUID> current = new ConcurrentHashMap<>();

    public CardService(IdCardsPlugin plugin) throws IOException {
        this.plugin = plugin;
        Path file = plugin.getDataFolder().toPath().resolve("secret.key");
        if (Files.exists(file)) {
            secret = HexFormat.of().parseHex(Files.readString(file).trim());
        } else {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            Files.createDirectories(file.getParent());
            Files.writeString(file, HexFormat.of().formatHex(secret) + "\n");
        }
        for (UUID[] row : plugin.db().load("SELECT owner, card_id FROM cards",
                rs -> new UUID[]{UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2))})) {
            current.put(row[0], row[1]);
        }
    }

    /** A new card bound to {@code owner}. With one-per-player this revokes the owner's earlier cards. */
    public ItemStack issue(UUID owner) {
        UUID cardId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        current.put(owner, cardId);
        plugin.db().write("INSERT INTO cards (owner, card_id, issued_at) VALUES (?,?,?) ON CONFLICT(owner) DO UPDATE SET "
                + "card_id = excluded.card_id, issued_at = excluded.issued_at", owner.toString(), cardId.toString(), now);
        ItemStack item = base();
        Lang lang = plugin.lang();
        String name = plugin.profiles().name(owner);
        item.setData(DataComponentTypes.ITEM_NAME, lang.get("item.card.name"));
        item.lore(List.of(
                plain(lang.get("item.card.lore-owner", Lang.ph("player", name == null ? owner.toString().substring(0, 8) : name))),
                plain(lang.get("item.card.lore-use"))));
        item.editPersistentDataContainer(pdc -> {
            pdc.set(plugin.keys().itemType, PersistentDataType.STRING, TYPE);
            pdc.set(plugin.keys().cardOwner, PersistentDataType.STRING, owner.toString());
            pdc.set(plugin.keys().cardId, PersistentDataType.STRING, cardId.toString());
            pdc.set(plugin.keys().cardIssued, PersistentDataType.LONG, now);
            pdc.set(plugin.keys().cardSignature, PersistentDataType.STRING, sign(owner, cardId, now));
        });
        return item;
    }

    /** The bare item every card is built on. */
    private static ItemStack base() {
        ItemStack item = ItemStack.of(BASE);
        item.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        // a spear only for its animation: holding the button does nothing to anybody
        item.setData(DataComponentTypes.KINETIC_WEAPON, KineticWeapon.kineticWeapon().delayTicks(0).build());
        item.setData(DataComponentTypes.USE_EFFECTS, UseEffects.useEffects().canSprint(true).speedMultiplier(1f).build());
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        for (DataComponentType type : new DataComponentType[]{DataComponentTypes.PIERCING_WEAPON, DataComponentTypes.WEAPON, DataComponentTypes.MAX_DAMAGE,
                DataComponentTypes.DAMAGE, DataComponentTypes.MINIMUM_ATTACK_CHARGE, DataComponentTypes.ENCHANTABLE}) {
            item.unsetData(type);
        }
        return item;
    }

    /**
     * Reads the main-hand item of a player; null when it is not a card. Cards made by IdCards 1.0 (paper) are turned
     * into the current kind on the way: same data, same name and lore, new base item.
     */
    public @Nullable CardData readHeld(Player p, ItemStack hand) {
        if (hand.getType() != BASE && hand.getType() != Material.PAPER) return null;
        if (!isCard(hand)) return null;
        if (hand.getType() != BASE) {
            hand = upgrade(hand);
            p.getInventory().setItemInMainHand(hand);
        }
        return read(hand);
    }

    /** A 1.0 paper card as a current card. */
    public ItemStack upgrade(ItemStack old) {
        ItemStack item = base();
        item.setData(DataComponentTypes.ITEM_NAME, plugin.lang().get("item.card.name"));
        if (old.lore() != null) item.lore(old.lore());
        var from = old.getPersistentDataContainer();
        item.editPersistentDataContainer(pdc -> {
            for (var key : new org.bukkit.NamespacedKey[]{plugin.keys().itemType, plugin.keys().cardOwner, plugin.keys().cardId, plugin.keys().cardSignature}) {
                String v = from.get(key, PersistentDataType.STRING);
                if (v != null) pdc.set(key, PersistentDataType.STRING, v);
            }
            Long issued = from.get(plugin.keys().cardIssued, PersistentDataType.LONG);
            if (issued != null) pdc.set(plugin.keys().cardIssued, PersistentDataType.LONG, issued);
        });
        return item;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public boolean isCard(@Nullable ItemStack item) {
        return TYPE.equals(plugin.keys().itemType(item));
    }

    /** Reads an item. Never trusts anything but the persistent data and its signature. */
    public CardData read(@Nullable ItemStack item) {
        if (!isCard(item)) return CardData.NONE;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        UUID owner, cardId;
        Long issued;
        String signature;
        try {
            String o = pdc.get(plugin.keys().cardOwner, PersistentDataType.STRING), c = pdc.get(plugin.keys().cardId, PersistentDataType.STRING);
            issued = pdc.get(plugin.keys().cardIssued, PersistentDataType.LONG);
            signature = pdc.get(plugin.keys().cardSignature, PersistentDataType.STRING);
            if (o == null || c == null || issued == null || signature == null) return new CardData(CardData.Status.MALFORMED, null, null, 0);
            owner = UUID.fromString(o);
            cardId = UUID.fromString(c);
        } catch (IllegalArgumentException e) {
            return new CardData(CardData.Status.MALFORMED, null, null, 0); // wrong data type or not a UUID
        }
        if (!java.security.MessageDigest.isEqual(sign(owner, cardId, issued).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
            return new CardData(CardData.Status.FORGED, owner, cardId, issued);
        }
        if (plugin.settings().cardOnePerPlayer && !cardId.equals(current.get(owner))) {
            return new CardData(CardData.Status.REVOKED, owner, cardId, issued);
        }
        return new CardData(CardData.Status.VALID, owner, cardId, issued);
    }

    /** The first card in the player's hands, main hand first; null when neither hand holds one. */
    public @Nullable ItemStack held(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isCard(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        return isCard(off) ? off : null;
    }

    /** True when the inventory holds a valid card of the player's own. */
    public boolean hasOwnCard(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            CardData d = read(item);
            if (d.valid() && p.getUniqueId().equals(d.owner())) return true;
        }
        return false;
    }

    /** Puts a new card of {@code owner} into {@code to}'s inventory, or at their feet when it is full. */
    public void give(Player to, UUID owner) {
        ItemStack card = issue(owner);
        to.getInventory().addItem(card).values().forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
    }

    private String sign(UUID owner, UUID cardId, long issued) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal((owner + "|" + cardId + "|" + issued).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(full, 0, 16);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
