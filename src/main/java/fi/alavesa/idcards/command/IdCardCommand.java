package fi.alavesa.idcards.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.block.BlockType;
import fi.alavesa.idcards.block.Mount;
import fi.alavesa.idcards.block.PlacedBlock;
import fi.alavesa.idcards.config.Lang;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** /idcard and its subcommands (Brigadier). */
public final class IdCardCommand {

    private final IdCardsPlugin plugin;
    private final Map<UUID, Long> selfIssued = new HashMap<>();

    public IdCardCommand(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("idcard")
                .then(Commands.literal("give").requires(perm("idcard.give"))
                        .executes(ctx -> giveCard(ctx, self(ctx), null))
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .executes(ctx -> giveCard(ctx, target(ctx), null))
                                // a card of somebody else, e.g. a replacement handed over by security
                                .then(Commands.argument("owner", StringArgumentType.word()).executes(ctx -> giveCard(ctx, target(ctx), StringArgumentType.getString(ctx, "owner"))))))
                .then(Commands.literal("get").requires(perm("idcard.self")).executes(this::selfService))
                .then(Commands.literal("reload").requires(perm("idcard.reload")).executes(ctx -> {
                    plugin.reload();
                    plugin.lang().send(ctx.getSource().getSender(), "command.reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(giveItem("scanner", "idcard.scanner.give", () -> plugin.blocks().type("scanner").createItem()))
                .then(giveItem("computer", "idcard.computer.give", () -> plugin.blocks().type("computer").createItem()))
                .then(giveItem("linktool", "idcard.linktool.give", () -> plugin.links().createTool()))
                .then(test())
                .build();
    }

    private static java.util.function.Predicate<CommandSourceStack> perm(String node) {
        return s -> s.getSender().hasPermission(node);
    }

    private LiteralArgumentBuilder<CommandSourceStack> giveItem(String name, String permission, Supplier<ItemStack> item) {
        return Commands.literal(name).requires(perm(permission))
                .then(Commands.literal("give")
                        .executes(ctx -> giveItem(ctx, self(ctx), name, item.get()))
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(ctx -> giveItem(ctx, target(ctx), name, item.get()))));
    }

    private @Nullable Player self(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) return p;
        return ctx.getSource().getSender() instanceof Player p ? p : null;
    }

    private static @Nullable Player target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Player> found = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        return found.isEmpty() ? null : found.getFirst();
    }

    private int giveItem(CommandContext<CommandSourceStack> ctx, @Nullable Player to, String name, ItemStack item) {
        CommandSender sender = ctx.getSource().getSender();
        if (to == null) {
            plugin.lang().send(sender, "command.player-needed");
            return 0;
        }
        to.getInventory().addItem(item).values().forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
        plugin.lang().send(sender, "command.given-item", Lang.ph("item", plugin.lang().get("item." + name + ".name")), Lang.ph("player", to.getName()));
        return Command.SINGLE_SUCCESS;
    }

    @SuppressWarnings("deprecation") // getOfflinePlayer(name): an admin typing a name is exactly its use
    private int giveCard(CommandContext<CommandSourceStack> ctx, @Nullable Player to, @Nullable String ownerName) {
        CommandSender sender = ctx.getSource().getSender();
        if (to == null) {
            plugin.lang().send(sender, "command.player-needed");
            return 0;
        }
        UUID owner = to.getUniqueId();
        String shownName = to.getName();
        if (ownerName != null) {
            Player online = Bukkit.getPlayerExact(ownerName);
            var offline = online != null ? online : Bukkit.getOfflinePlayerIfCached(ownerName);
            if (offline == null) {
                try {
                    owner = UUID.fromString(ownerName); // a UUID works for players who never joined
                    shownName = ownerName;
                } catch (IllegalArgumentException e) {
                    plugin.lang().send(sender, "command.unknown-player", Lang.ph("player", ownerName));
                    return 0;
                }
            } else {
                owner = offline.getUniqueId();
                shownName = offline.getName() == null ? ownerName : offline.getName();
            }
        }
        plugin.cards().give(to, owner);
        plugin.lang().send(sender, "command.given-card", Lang.ph("owner", shownName), Lang.ph("player", to.getName()));
        if (!sender.equals(to)) plugin.lang().send(to, "card.received");
        return Command.SINGLE_SUCCESS;
    }

    private int selfService(CommandContext<CommandSourceStack> ctx) {
        Player p = self(ctx);
        if (p == null) return 0;
        Lang lang = plugin.lang();
        if (!plugin.settings().cardSelfService) {
            lang.send(p, "command.self-disabled");
            return 0;
        }
        if (plugin.settings().cardOnePerPlayer && plugin.cards().hasOwnCard(p)) {
            lang.send(p, "command.self-has-card");
            return 0;
        }
        long now = System.currentTimeMillis(), wait = plugin.settings().cardSelfCooldownSeconds * 1000L;
        Long last = selfIssued.get(p.getUniqueId());
        if (last != null && now - last < wait) {
            lang.send(p, "command.self-cooldown", Lang.ph("seconds", (wait - (now - last) + 999) / 1000));
            return 0;
        }
        selfIssued.put(p.getUniqueId(), now);
        plugin.cards().give(p, p.getUniqueId());
        lang.send(p, "card.received");
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /idcard test: everything a click does, as a command

    /**
     * For admins and automated test rigs: each subcommand runs the same code path as the click or key it stands for,
     * so a server console can drive the whole plugin for a player. Permission idcard.admin.
     */
    private LiteralArgumentBuilder<CommandSourceStack> test() {
        return Commands.literal("test").requires(perm("idcard.admin"))
                .then(Commands.literal("place").then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                                .then(Commands.argument("facing", StringArgumentType.word())
                                        .executes(ctx -> testPlace(ctx, "floor"))
                                        .then(Commands.argument("mount", StringArgumentType.word()).executes(ctx -> testPlace(ctx, StringArgumentType.getString(ctx, "mount"))))))))
                .then(Commands.literal("scan").then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("scanner", IntegerArgumentType.integer(1)).executes(ctx -> {
                            Player p = target(ctx);
                            PlacedBlock b = plugin.blocks().byId(IntegerArgumentType.getInteger(ctx, "scanner"));
                            if (p != null && b != null) b.type.interact(p, b);
                            return Command.SINGLE_SUCCESS;
                        }))))
                .then(Commands.literal("link").then(Commands.argument("scanner", IntegerArgumentType.integer(1))
                        .then(Commands.argument("computer", IntegerArgumentType.integer(1)).executes(ctx -> {
                            PlacedBlock s = plugin.blocks().byId(IntegerArgumentType.getInteger(ctx, "scanner")), c = plugin.blocks().byId(IntegerArgumentType.getInteger(ctx, "computer"));
                            if (s != null && c != null && s.type.id().equals("scanner") && c.type.id().equals("computer")) {
                                plugin.links().link(s, c);
                                plugin.computers().refreshAll();
                            }
                            return Command.SINGLE_SUCCESS;
                        }))))
                .then(Commands.literal("log").then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("computer", IntegerArgumentType.integer(1)).executes(ctx -> {
                            Player p = target(ctx);
                            PlacedBlock c = plugin.blocks().byId(IntegerArgumentType.getInteger(ctx, "computer"));
                            if (p != null && c != null) plugin.computers().openLog(p, c, 0);
                            return Command.SINGLE_SUCCESS;
                        }))))
                // the three inputs a console cannot make: they fire the same Bukkit events a real click or key does
                .then(Commands.literal("click").then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                                .then(Commands.argument("face", StringArgumentType.word()).executes(ctx -> {
                                    Player p = target(ctx);
                                    if (p == null) return 0;
                                    var pos = ctx.getArgument("pos", BlockPositionResolver.class).resolve(ctx.getSource());
                                    BlockFace face = BlockFace.valueOf(StringArgumentType.getString(ctx, "face").toUpperCase(Locale.ROOT));
                                    var event = new org.bukkit.event.player.PlayerInteractEvent(p, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                                            p.getInventory().getItemInMainHand(), p.getWorld().getBlockAt(pos.blockX(), pos.blockY(), pos.blockZ()), face,
                                            org.bukkit.inventory.EquipmentSlot.HAND);
                                    Bukkit.getPluginManager().callEvent(event);
                                    ctx.getSource().getSender().sendMessage("right-click fired");
                                    return Command.SINGLE_SUCCESS;
                                })))))
                // as if the right mouse button were held with the card, until the command is given again
                .then(Commands.literal("hold").then(Commands.argument("player", ArgumentTypes.player()).executes(ctx -> {
                    Player p = target(ctx);
                    if (p == null) return 0;
                    ctx.getSource().getSender().sendMessage("held out: " + plugin.presenting().toggleForced(p));
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("list").executes(ctx -> {
                    for (PlacedBlock b : plugin.blocks().all()) {
                        ctx.getSource().getSender().sendMessage("#" + b.id + " " + b.type.id() + " " + b.label() + " " + b.worldName + " " + b.x + "," + b.y + "," + b.z
                                + " " + b.facing + " " + b.mount + (b.spawned() ? "" : " (not loaded)"));
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private int testPlace(CommandContext<CommandSourceStack> ctx, String mountName) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        BlockType type = plugin.blocks().type(StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT));
        BlockFace facing;
        Mount mount;
        try {
            facing = BlockFace.valueOf(StringArgumentType.getString(ctx, "facing").toUpperCase(Locale.ROOT));
            mount = Mount.valueOf(mountName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            facing = null;
            mount = null;
        }
        if (type == null || facing == null || facing.getModY() != 0 || !facing.isCartesian() || !type.allows(mount)) {
            sender.sendMessage("usage: /idcard test place <scanner|computer> <x y z> <north|east|south|west> [floor|wall]");
            return 0;
        }
        var pos = ctx.getArgument("pos", BlockPositionResolver.class).resolve(ctx.getSource());
        var cell = ctx.getSource().getLocation().getWorld().getBlockAt(pos.blockX(), pos.blockY(), pos.blockZ());
        if (plugin.blocks().at(cell) != null) {
            sender.sendMessage("there is a block of ours already");
            return 0;
        }
        PlacedBlock b = plugin.blocks().create(type, cell, facing, mount, null);
        sender.sendMessage(b == null ? "failed" : "placed #" + b.id + " " + b.label());
        return Command.SINGLE_SUCCESS;
    }
}
