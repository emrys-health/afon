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
 * their own, in {@code dataProviders.named}: so they are found by looking through the project stacks of the Afon home, and each one's egress IP is what its cluster
 * leaves from, the one its file names, else that of the config it is made from.  A Data Provider's Polaris then admits those, and no other TRE.
 */
final class TreEgress {

    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    private TreEgress() {
    }

    /**
     * @param afonHome the Afon home, whose project stacks are looked through
     * @param data     the Data Provider's project stack
     * @return the egress IPs of the TRE project stacks that name a catalog of the Data Provider, in the order of their ids, each once
     * @throws ProjectStackException if such a TRE has no egress IP, which would leave it locked out of the Data Provider with nothing to say so
     */
    static @NonNull List<String> of(@NonNull Path afonHome, @NonNull DataProjectStack data) {
        Set<String> addresses = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (TreProjectStack tre : readers(afonHome, data)) {
            String ip = egressIp(afonHome, tre);
            if (ip == null) {
                problems.add(String.format("project stack %s reads this Data Provider, but leaves from no known address, so it could not be admitted: set project-stack.egressIp in its file, "
                                           + "or %s_EGRESS_IP in the config it is made from", tre.id(), tre.cloud().name()));
            } else {
                addresses.add(ip);
            }
        }
        if (!problems.isEmpty()) {
            throw new ProjectStackException(FileProjectStackProvider.FILE, problems);
        }
        return List.copyOf(addresses);
    }

    /** @return the TRE project stacks of the Afon home that name a catalog of the Data Provider, a file that is not valid having none to name */
    private static List<TreProjectStack> readers(Path afonHome, DataProjectStack data) {
        List<TreProjectStack> readers = new ArrayList<>();
        Path dir = afonHome.resolve(FileProjectStackProvider.PROJECTS_DIR);
        if (!Files.isDirectory(dir)) {
            return readers;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                if (child.getFileName().toString().equals(data.id().value()) || !Files.isRegularFile(child.resolve(FileProjectStackProvider.FILE))) {
                    continue;
                }
                try {
                    if (FileProjectStackProvider.forProjectStack(afonHome, child.getFileName().toString()).getProjectStack() instanceof TreProjectStack tre
                        && tre.dataProviders().stream().anyMatch(provider -> data.id().value().equals(provider.projectStack()))) {
                        readers.add(tre);
                    }
                } catch (ProjectStackException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
                    // a project stack whose file is not valid, or whose own Data Providers cannot be resolved, is not one that this Data Provider can be sure to serve
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return readers;
    }

    private static String egressIp(Path afonHome, TreProjectStack tre) {
        if (tre.egressIp() != null) {
            return tre.egressIp();
        }
        try {
            Path envConfig = tre.env() != null ? ProjectStackEnv.envConfigDir(afonHome, tre.env()) : Path.of(configDir());
            String inherited = ProjectStackEnv.envProperties(envConfig).getProperty(tre.cloud().name() + "_EGRESS_IP", "").trim();
            return IPV4.matcher(inherited).matches() ? inherited : null;
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            return null;
        }
    }
}
