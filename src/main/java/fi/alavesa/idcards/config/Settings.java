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

    public final double uiScale, uiHintScale;
    public final int uiHintX, uiHintY, uiOpenCooldownTicks;
    public final boolean uiEffects, uiCloseOnDamage, uiSounds;
    /** auto | camera | mount */
    public final String freezeMethod;

    public final int presentCooldownTicks, presentUpdateInterval, presentMaxSeconds;
    public final double presentRenderDistance;
    public final boolean inspectEnabled;
    public final double inspectRange, inspectScale;
    public final int inspectInterval, inspectOffsetX, inspectOffsetY;

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
    public final boolean packRequired, packTextShader;
    /** kick | disable */
    public final String packOnDecline;
    public final String packRadioZip, packSuperbVehiclesZip, packCctvZip;

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

        uiScale = c.getDouble("ui.scale", 0);
        uiHintX = c.getInt("ui.hint.x", 6);
        uiHintY = c.getInt("ui.hint.y", 6);
        uiHintScale = c.getDouble("ui.hint.scale", 1);
        uiEffects = c.getBoolean("ui.effects", true);
        uiCloseOnDamage = c.getBoolean("ui.close-on-damage", true);
        uiSounds = c.getBoolean("ui.sounds", true);
        uiOpenCooldownTicks = c.getInt("ui.open-cooldown-ticks", 10);
        freezeMethod = choice(c, "ui.freeze-method", "auto", "auto", "camera", "mount");

        presentCooldownTicks = c.getInt("present.cooldown-ticks", 20);
        presentUpdateInterval = Math.max(1, c.getInt("present.update-interval-ticks", 10));
        presentMaxSeconds = c.getInt("present.max-seconds", 0);
        presentRenderDistance = c.getDouble("present.render-distance", 32);
        inspectEnabled = c.getBoolean("inspect.enabled", true);
        inspectRange = c.getDouble("inspect.range", 5);
        inspectInterval = Math.max(1, c.getInt("inspect.interval-ticks", 4));
        inspectScale = c.getDouble("inspect.scale", 1);
        inspectOffsetX = c.getInt("inspect.offset-x", 6);
        inspectOffsetY = c.getInt("inspect.offset-y", 0);

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
        packSendDelayTicks = Math.max(0, c.getInt("pack.send-delay-ticks", 60));
        packRequired = c.getBoolean("pack.required", true);
        packOnDecline = choice(c, "pack.on-decline", "kick", "kick", "disable");
        packTextShader = c.getBoolean("pack.text-shader.merge", true);
        packRadioZip = c.getString("pack.text-shader.radio-zip", "Radio/radio-pack.zip");
        packSuperbVehiclesZip = c.getString("pack.text-shader.superbvehicles-zip", "SuperbVehicles/pack/SuperbVehicles-hud.zip");
        packCctvZip = c.getString("pack.text-shader.cctv-zip", "CCTV/cctv-pack.zip");

        // "hide-nametags" is where IdCards 0.x kept this
        hideNametags = c.getBoolean("nametags.hide", c.getBoolean("hide-nametags", false));
    }

    private static String choice(FileConfiguration c, String path, String fallback, String... allowed) {
        String v = c.getString(path, fallback).toLowerCase(Locale.ROOT);
        for (String a : allowed) if (a.equals(v)) return v;
        return fallback;
    }
}
