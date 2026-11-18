package fi.alavesa.idcards.resourcepack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the IdCards resource pack zip. Source is the {@code resourcepack/} folder bundled in the plugin jar, or a folder
 * on disk (pack.source-folder) so you can tweak shaders without rebuilding the plugin. Entries are sorted and carry a
 * fixed timestamp, so the same sources always give the same sha1 (clients then reuse their cached copy).
 */
public final class PackBuilder {

    public record Built(byte[] zip, String sha1, int files) {
    }

    private static final String PREFIX = "resourcepack/";
    private static final FileTime FIXED_TIME = FileTime.fromMillis(1_700_000_000_000L);

    private PackBuilder() {
    }

    public static Built fromJar(File pluginJar, Map<String, byte[]> extra) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (JarFile jar = new JarFile(pluginJar)) {
            for (JarEntry e : java.util.Collections.list(jar.entries())) {
                if (e.isDirectory() || !e.getName().startsWith(PREFIX)) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    files.put(e.getName().substring(PREFIX.length()), in.readAllBytes());
                }
            }
        }
        files.putAll(extra);
        return zip(files);
    }

    public static Built fromFolder(Path root, Map<String, byte[]> extra) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                if (rel.endsWith(".DS_Store")) continue;
                files.put(rel, Files.readAllBytes(p));
            }
        }
        files.putAll(extra);
        return zip(files);
    }

    private static Built zip(Map<String, byte[]> files) throws IOException {
        if (!files.containsKey("pack.mcmeta")) throw new IOException("pack source has no pack.mcmeta");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                ZipEntry e = new ZipEntry(f.getKey());
                e.setLastModifiedTime(FIXED_TIME);
                zip.putNextEntry(e);
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
        byte[] data = bytes.toByteArray();
        return new Built(data, sha1(data), files.size());
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
