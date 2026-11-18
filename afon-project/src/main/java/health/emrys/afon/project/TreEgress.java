package health.emrys.afon.project;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static health.emrys.afon.config.ConfigUtils.configDir;

/**
 * The addresses that the TREs of an Afon home reach a Data Provider from.  The Data Provider's file does not list the TREs that read it, as they name it in
 * their own, in {@code dataProviders.named}: so they are found by looking through the projects of the Afon home, and each one's egress IP is what its cluster
 * leaves from, the one its file names, else that of the config it is made from.  A Data Provider's Polaris then admits those, and no other TRE.
 */
final class TreEgress {

    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    private TreEgress() {
    }

    /**
     * @param afonHome the Afon home, whose projects are looked through
     * @param data     the Data Provider's project
     * @return the egress IPs of the TRE projects that name a catalog of the Data Provider, in the order of their ids, each once
     * @throws ProjectException if such a TRE has no egress IP, which would leave it locked out of the Data Provider with nothing to say so
     */
    static @NonNull List<String> of(@NonNull Path afonHome, @NonNull DataProject data) {
        Set<String> addresses = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (TreProject tre : readers(afonHome, data)) {
            String ip = egressIp(afonHome, tre);
            if (ip == null) {
                problems.add(String.format("project %s reads this Data Provider, but leaves from no known address, so it could not be admitted: set project.egressIp in its file, "
                                           + "or %s_EGRESS_IP in the config it is made from", tre.id(), tre.cloud().name()));
            } else {
                addresses.add(ip);
            }
        }
        if (!problems.isEmpty()) {
            throw new ProjectException(FileProjectProvider.FILE, problems);
        }
        return List.copyOf(addresses);
    }

    /** @return the TRE projects of the Afon home that name a catalog of the Data Provider, a file that is not valid having none to name */
    private static List<TreProject> readers(Path afonHome, DataProject data) {
        List<TreProject> readers = new ArrayList<>();
        Path dir = afonHome.resolve(FileProjectProvider.PROJECTS_DIR);
        if (!Files.isDirectory(dir)) {
            return readers;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                if (child.getFileName().toString().equals(data.id().value()) || !Files.isRegularFile(child.resolve(FileProjectProvider.FILE))) {
                    continue;
                }
                try {
                    if (FileProjectProvider.forProject(afonHome, child.getFileName().toString()).getProject() instanceof TreProject tre
                        && tre.dataProviders().stream().anyMatch(provider -> data.id().value().equals(provider.project()))) {
                        readers.add(tre);
                    }
                } catch (ProjectException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
                    // a project whose file is not valid, or whose own Data Providers cannot be resolved, is not one that this Data Provider can be sure to serve
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return readers;
    }

    private static String egressIp(Path afonHome, TreProject tre) {
        if (tre.egressIp() != null) {
            return tre.egressIp();
        }
        try {
            Path envConfig = tre.env() != null ? ProjectEnv.envConfigDir(afonHome, tre.env()) : Path.of(configDir());
            String inherited = ProjectEnv.envProperties(envConfig).getProperty(tre.cloud().name() + "_EGRESS_IP", "").trim();
            return IPV4.matcher(inherited).matches() ? inherited : null;
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            return null;
        }
    }
}
