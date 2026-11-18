package fi.alavesa.idcards.luckperms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.jspecify.annotations.Nullable;

/**
 * Turns whatever an admin typed as a LuckPerms prefix into safe, coloured text.
 *
 * <p>Accepted: legacy codes with {@code &} or {@code §} ({@code &c}, {@code &l}, {@code &r}), hex in the forms
 * {@code &#RRGGBB}, {@code #RRGGBB}-after-ampersand {@code &x&R&R&G&G&B&B} and {@code <#RRGGBB>}, and MiniMessage
 * colour tags ({@code <red>}, {@code <gradient:...>}, {@code <bold>}). Legacy codes are first rewritten as
 * MiniMessage tags, then one restricted MiniMessage instance parses the result: it knows colours, decorations,
 * gradients and reset only, so a prefix can never carry click or hover events, key binds, selectors or NBT lookups.
 * Anything it does not know stays visible as plain text.
 */
public final class PrefixFormatter {

    /** A stretch of text in one colour (null = the card's default ink). */
    public record Run(String text, @Nullable TextColor color) {
    }

    private static final MiniMessage SAFE = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
                    StandardTags.rainbow(), StandardTags.reset()))
            .build();
    private static final Pattern BUNGEE_HEX = Pattern.compile("[&§]x((?:[&§][0-9a-fA-F]){6})");
    private static final Pattern AMP_HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");
    private static final String[] COLOR_NAMES = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};

    private PrefixFormatter() {
    }

    public static Component component(String raw) {
        return SAFE.deserialize(legacyToTags(raw));
    }

    /** The prefix as coloured runs; decorations are dropped (the card font has one weight). */
    public static List<Run> runs(String raw) {
        List<Run> out = new ArrayList<>();
        Deque<Style> styles = new ArrayDeque<>();
        ComponentFlattener.basic().flatten(component(raw), new FlattenerListener() {
            @Override
            public void pushStyle(Style style) {
                Style parent = styles.peek();
                styles.push(parent == null ? style : parent.merge(style));
            }

            @Override
            public void component(String text) {
                if (text.isEmpty()) return;
                Style style = styles.peek();
                TextColor color = style == null ? null : style.color();
                if (!out.isEmpty() && java.util.Objects.equals(out.getLast().color(), color)) {
                    out.set(out.size() - 1, new Run(out.getLast().text() + text, color));
                } else {
                    out.add(new Run(text, color));
                }
            }

            @Override
            public void popStyle(Style style) {
                styles.pop();
            }
        });
        return out;
    }

    /** The prefix without any colour. */
    public static String plain(String raw) {
        StringBuilder sb = new StringBuilder();
        for (Run run : runs(raw)) sb.append(run.text());
        return sb.toString();
    }

    static String legacyToTags(String raw) {
        String s = raw;
        Matcher m = BUNGEE_HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, "<#" + m.group(1).replaceAll("[&§]", "") + ">");
        m.appendTail(sb);
        s = AMP_HEX.matcher(sb.toString()).replaceAll("<#$1>");
        m = LEGACY.matcher(s);
        sb = new StringBuilder();
        while (m.find()) {
            char code = Character.toLowerCase(m.group(1).charAt(0));
            String tag = switch (code) {
                case 'k' -> "<obfuscated>";
                case 'l' -> "<bold>";
                case 'm' -> "<strikethrough>";
                case 'n' -> "<underlined>";
                case 'o' -> "<italic>";
                case 'r' -> "<reset>";
                // like the legacy format, a colour code also ends the decorations before it
                default -> "<reset><" + COLOR_NAMES[Character.digit(code, 16)] + ">";
            };
            m.appendReplacement(sb, tag);
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
