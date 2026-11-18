package fi.alavesa.idcards.resourcepack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Decides which {@code minecraft:core/text} shader the pack ships.
 *
 * <p>Minecraft runs only ONE copy of a core shader: the one in the topmost pack. Radio, SuperbVehicles and CCTV
 * replace the same two files for their HUDs, so when any of them is installed this pack must not ship a text shader
 * that drops theirs. It then starts from the most complete shader it can find on disk (Radio's pack already merges
 * SuperbVehicles' and CCTV's hooks; else SuperbVehicles', which merges CCTV's; else CCTV's) and adds the two IdCards
 * blocks to it, copying the other plugins' include files along. With none of them installed, the pack's own
 * files (vanilla 26.3 plus the IdCards blocks) are used as they are.
 *
 * <p>Every block is inserted after a line that exists in the vanilla shader. When such a line is missing (another
 * plugin changed its shader in a way this does not know) the merge is refused and the stand-alone files are shipped:
 * a core shader that does not compile makes the client drop every server pack, so the safe result is a pack that
 * compiles, at the price of the other plugins' HUD effects while this pack is on top. The log says so.
 */
public final class TextShaderMerge {

    /** Files to put into the pack over its own (empty = keep the pack's own shader), and what happened. */
    public record Result(Map<String, byte[]> files, String description) {
    }

    private static final String CORE = "assets/minecraft/shaders/core/text.";
    private static final String V_INCLUDE_AFTER = "#include <minecraft:projection.glsl>\n";
    private static final String V_CALL_AFTER = "texCoord0 = UV0;\n";
    private static final String F_INCLUDE_AFTER = "#include <minecraft:oit.glsl>\n";
    private static final String F_CALL_AFTER = "vec4 color = texColor * vertexColor * ColorModulator;\n";

    private TextShaderMerge() {
    }

    private record Source(String name, String vertex, String fragment, Map<String, byte[]> includes) {
    }

    /** @param candidates other plugins' pack zips, most complete first; missing files are skipped */
    public static Result build(List<Map.Entry<String, File>> candidates, Consumer<String> warn) {
        for (Map.Entry<String, File> c : candidates) {
            Source s = read(c.getKey(), c.getValue(), warn);
            if (s == null) continue;
            try {
                if (s.vertex.contains("idcards_text_vertex") || s.fragment.contains("idcards_text_fragment")) {
                    throw new IllegalStateException("it already carries the IdCards hooks");
                }
                String vertex = insertAfter(s.vertex, V_INCLUDE_AFTER, "// ===== IDCARDS BEGIN =====\n#include <idcards:idcards_text_vertex.glsl>\n// ===== IDCARDS END =====\n");
                vertex = insertAfter(vertex, V_CALL_AFTER, "    // ===== IDCARDS BEGIN =====\n    idcards_text_vertex(Color);\n    // ===== IDCARDS END =====\n");
                String fragment = insertAfter(s.fragment, F_INCLUDE_AFTER, "// ===== IDCARDS BEGIN =====\n#include <idcards:idcards_text_fragment.glsl>\n// ===== IDCARDS END =====\n");
                fragment = insertAfter(fragment, F_CALL_AFTER, "    // ===== IDCARDS BEGIN =====\n    color = idcards_text_fragment(color, texColor, vertexColor, ColorModulator);\n    // ===== IDCARDS END =====\n");
                Map<String, byte[]> files = new LinkedHashMap<>(s.includes);
                files.put(CORE + "vsh", vertex.getBytes(StandardCharsets.UTF_8));
                files.put(CORE + "fsh", fragment.getBytes(StandardCharsets.UTF_8));
                return new Result(files, "merged into " + s.name + "'s");
            } catch (IllegalStateException e) {
                warn.accept("the text shader of " + s.name + " cannot be merged (" + e.getMessage() + "). The pack ships its own text shader; "
                        + s.name + "'s HUD effects will be missing while the IdCards pack is on top.");
                return new Result(Map.of(), "own (merge with " + s.name + " refused)");
            }
        }
        return new Result(Map.of(), "own (no other text-shader plugin found)");
    }

    private static String insertAfter(String s, String anchor, String add) {
        int i = s.indexOf(anchor);
        if (i < 0) throw new IllegalStateException("line not found: " + anchor.trim());
        if (s.indexOf(anchor, i + 1) >= 0) throw new IllegalStateException("line found twice: " + anchor.trim());
        return s.substring(0, i + anchor.length()) + add + s.substring(i + anchor.length());
    }

    /**
     * The 26.3 text shader and the non-vanilla shader includes of a pack zip; null when the zip is missing or has
     * no 26.3 text shader. An overlay directory (CCTV's cctv_26_3/) wins over the pack root.
     */
    private static @Nullable Source read(String name, File zip, Consumer<String> warn) {
        if (!zip.isFile()) return null;
        try (ZipFile z = new ZipFile(zip)) {
            String vertex = null, fragment = null;
            Map<String, byte[]> root = new LinkedHashMap<>(), overlay = new LinkedHashMap<>();
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.isDirectory()) continue;
                String path = e.getName();
                int assets = path.indexOf("assets/");
                if (assets < 0 || (assets > 0 && path.lastIndexOf('/', assets - 2) >= 0)) continue; // root or one overlay folder only
                boolean inOverlay = assets > 0;
                String rel = path.substring(assets);
                if (rel.equals(CORE + "vsh") || rel.equals(CORE + "fsh")) {
                    String text = text(z, e);
                    if (!text.contains("#include <minecraft:")) continue; // 26.2 syntax: not usable on 26.3
                    boolean isVertex = rel.endsWith("vsh");
                    if (isVertex && (vertex == null || inOverlay)) vertex = text;
                    if (!isVertex && (fragment == null || inOverlay)) fragment = text;
                } else if (rel.matches("assets/[a-z0-9_.-]+/shaders/include/.+") && !rel.startsWith("assets/minecraft/") && !rel.startsWith("assets/idcards/")) {
                    try (InputStream in = z.getInputStream(e)) {
                        (inOverlay ? overlay : root).put(rel, in.readAllBytes());
                    }
                }
            }
            if (vertex == null || fragment == null) return null;
            root.putAll(overlay);
            return new Source(name, vertex, fragment, root);
        } catch (IOException e) {
            warn.accept("could not read " + zip + " (" + e.getMessage() + "); " + name + "'s text shader is not merged");
            return null;
        }
    }

    private static String text(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
