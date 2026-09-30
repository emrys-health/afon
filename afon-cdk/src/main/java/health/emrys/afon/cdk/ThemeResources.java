package health.emrys.afon.cdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads one theme's resource tree off the afon-theme module's synth classpath — every plain file
 * under {@code themes/<theme>/<app>/}, so that GuacamoleConfig and KeyCloakConfig can build their
 * ConfigMaps from whatever files a theme actually ships, rather than a hardcoded list. That's what
 * lets a new theme be added as pure resources (see afon-theme), with no code changes here.
 */
final class ThemeResources {

    private ThemeResources() {}

    /**
     * Every regular file under classpath {@code /prefix} (which must end in {@code /}), keyed by its
     * path relative to {@code prefix} (posix-style, no leading slash). {@code markerFile} — e.g.
     * {@code "guac-manifest.json"} or {@code "theme.properties"} — is a file expected at
     * {@code prefix + markerFile}; its resolved {@link URL} both confirms the theme exists and
     * anchors classpath lookup.
     *
     * <p>Works whether the afon-theme module is unpacked (IDE, {@code target/classes}) or packaged
     * in a jar — including Spring Boot's {@code nested:} scheme when that jar lives inside
     * afon-cli.jar's {@code BOOT-INF/lib}, where a {@link JarURLConnection} hands back the
     * containing jar's own URL rather than a resolvable filesystem {@link Path}. Since that jar
     * holds every theme's resources, not just the requested one's, its entries are filtered down to
     * just those under {@code prefix}.
     */
    static Map<String, byte[]> list(String prefix, String markerFile) {
        URL marker = ThemeResources.class.getResource("/" + prefix + markerFile);
        if (marker == null) {
            throw new IllegalStateException(
                "Theme resource \"" + markerFile + "\" not found under /" + prefix
                + " — check AFON_THEME and that afon-theme was built");
        }
        try {
            if ("jar".equals(marker.getProtocol())) {
                JarURLConnection jarConnection = (JarURLConnection) marker.openConnection();
                try (InputStream in = jarConnection.getJarFileURL().openStream()) {
                    return listFromJar(in, prefix);
                }
            }
            return listFromDirectory(Path.of(marker.toURI()).getParent());
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Could not read theme resources under /" + prefix, e);
        }
    }

    private static Map<String, byte[]> listFromJar(InputStream jarBytes, String prefix) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(jarBytes)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                files.put(entry.getName().substring(prefix.length()), in.readAllBytes());
            }
        }
        return files;
    }

    private static Map<String, byte[]> listFromDirectory(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                try {
                    files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return files;
    }
}
