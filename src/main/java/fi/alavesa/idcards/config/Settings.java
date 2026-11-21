package fi.alavesa.idcards.config;

import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml, read once into fields. A reload builds a new object. */
public final class Settings {

    public final String language;

    public final boolean cardOnePerPlayer, cardSelfService, cardGiveOnJoin, cardAllowDrop, cardAllowContainers;
    public final int cardSelfCooldownSeconds;
    /** keep | drop | destroy */
    public final String cardOnDeath;

    public final int presentCooldownTicks;
    public final double presentRenderDistance;

    public final int scannerCooldownTicks, scannerSwipeTicks, scannerResultTicks;
    public final double scannerPresentRange;
    public final float scannerVolume;

    public final int computerScreenTimeoutSeconds, computerLogSize, computerLogPageSize;
    public final double computerAutoLinkRadius;
    public final String timeFormat, timeZone;

    public final int blockBreakHits, blockBreakWindow, blockValidateInterval;
    public final double blockViewRange;

    public final String fallbackPrefix;
    public final int prefixRefreshSeconds;

    /** auto | same-port | http | url | none */
    public final String packDelivery;
    public final String packUrl, packSha1, packHttpBind, packPublicHost, packPrompt;
    public final int packHttpPort, packSendDelayTicks;
    public final boolean packRequired;

    public final boolean hideNametags;

    public Settings(FileConfiguration c) {
        language = c.getString("language", "en");

        cardOnePerPlayer = c.getBoolean("card.one-per-player", true);
        cardSelfService = c.getBoolean("card.self-service.enabled", false);
        cardSelfCooldownSeconds = c.getInt("card.self-service.cooldown-seconds", 300);
        cardGiveOnJoin = c.getBoolean("card.give-on-join", false);
        cardAllowDrop = c.getBoolean("card.allow-drop", true);
        cardAllowContainers = c.getBoolean("card.allow-containers", true);
        cardOnDeath = choice(c, "card.on-death", "keep", "keep", "drop", "destroy");

        presentCooldownTicks = c.getInt("present.cooldown-ticks", 10);
        presentRenderDistance = c.getDouble("present.render-distance", 32);

        scannerCooldownTicks = c.getInt("scanner.cooldown-ticks", 20);
        scannerSwipeTicks = Math.max(4, c.getInt("scanner.swipe-ticks", 14));
        scannerResultTicks = Math.max(1, c.getInt("scanner.result-ticks", 40));
        scannerPresentRange = c.getDouble("scanner.present-range", 2.5);
        scannerVolume = (float) c.getDouble("scanner.volume", 1.0);

        computerScreenTimeoutSeconds = c.getInt("computer.screen-timeout-seconds", 15);
        computerLogSize = Math.max(1, c.getInt("computer.log.size", 60));
        computerLogPageSize = Math.max(1, Math.min(12, c.getInt("computer.log.page-size", 8)));
        computerAutoLinkRadius = c.getBoolean("computer.auto-link.enabled", false) ? c.getDouble("computer.auto-link.radius", 8) : 0;
        timeFormat = c.getString("computer.time-format", "HH:mm:ss");
        timeZone = c.getString("computer.time-zone", "");

        blockBreakHits = c.getInt("blocks.break-hits", 4);
        blockBreakWindow = c.getInt("blocks.break-window-ticks", 30);
        blockValidateInterval = c.getInt("blocks.validate-interval-ticks", 100);
        blockViewRange = c.getDouble("blocks.view-range", 1.0);

        fallbackPrefix = c.getString("luckperms.fallback-prefix", "&7Personnel");
        prefixRefreshSeconds = c.getInt("luckperms.refresh-seconds", 60);

        packDelivery = choice(c, "pack.delivery", "auto", "auto", "same-port", "http", "url", "none");
        packUrl = c.getString("pack.url", "");
        packSha1 = c.getString("pack.sha1", "").trim().toLowerCase(Locale.ROOT);
        packHttpBind = c.getString("pack.http.bind", "0.0.0.0");
        packHttpPort = c.getInt("pack.http.port", 8166);
        packPublicHost = c.getString("pack.public-host", "");
        packPrompt = c.getString("pack.prompt", "");
        packSendDelayTicks = Math.max(0, c.getInt("pack.send-delay-ticks", 20));
        packRequired = c.getBoolean("pack.required", false);

        // "hide-nametags" is where IdCards 0.x kept this
        hideNametags = c.getBoolean("nametags.hide", c.getBoolean("hide-nametags", false));
    }

    private static String choice(FileConfiguration c, String path, String fallback, String... allowed) {
        String v = c.getString(path, fallback).toLowerCase(Locale.ROOT);
        for (String a : allowed) if (a.equals(v)) return v;
        return fallback;
    }
}
