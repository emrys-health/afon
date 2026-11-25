package health.emrys.afon.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Afon home that a project stack's commands run in.  The scripts and commands look for the config in {@code $AFON_HOME/config}, which an env
 * names by switching a symlink for everyone, so a project stack gets a home of its own instead: {@code <afon-home>/projects/<project-stack>/home}, whose
 * {@code config} and {@code cdktf} are those of the project stack, {@code config-<project-stack>} and {@code cdktf-<project-stack>}, and whose other entries are
 * those of the Afon home (the CLI, logs, keys), so that running one project stack never switches the config of another, nor the one the deployer is
 * working in.
 */
public final class ProjectStackHome {

    public static final String HOME_DIR = "home";

    /**
     * What the Afon home has that is not shared with a project stack's home: the config and cdktf that are active, and the envs.  The project stacks are,
     * so that a command in the project stack's home finds its project stack file and its secrets.
     */
    private static final Set<String> NOT_SHARED = Set.of("config", "cdktf");

    private ProjectStackHome() {
    }

    /**
     * @return the home of the project stack, {@code <afon-home>/projects/<project-stack>/home}
     */
    public static Path dir(Path afonHome, ProjectStack projectStack) {
        return FileProjectStackProvider.projectDir(afonHome, projectStack.id().value()).resolve(HOME_DIR);
    }

    /**
     * Makes the home of the project stack, replacing the one it had: links to the config and cdktf of the project stack and to the other entries of the
     * Afon home.
     *
     * @return the home
     * @throws IllegalStateException if the project stack has no config env yet, ie it is not configured, see {@link ProjectStackEnv#configure}
     * @throws UncheckedIOException  if the links cannot be made
     */
    public static Path prepare(Path afonHome, ProjectStack projectStack) {
        Path config = ProjectStackEnv.configDir(afonHome, projectStack);
        Path cdktf = ProjectStackEnv.cdktfDir(afonHome, projectStack);
        if (!Files.isDirectory(config) || !Files.isDirectory(cdktf)) {
            throw new IllegalStateException(String.format(
                "Project stack %s is not configured: %s or %s is missing; run manage-project-stack configure %s", projectStack.id(), config, cdktf, projectStack.id()));
        }
        Path home = dir(afonHome, projectStack);
        try {
            deleteLinks(home);
            Files.createDirectories(home);
            Files.createSymbolicLink(home.resolve("config"), config.toAbsolutePath());
            Files.createSymbolicLink(home.resolve("cdktf"), cdktf.toAbsolutePath());
            try (Stream<Path> entries = Files.list(afonHome)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    String name = entry.getFileName().toString();
                    if (!NOT_SHARED.contains(name) && !name.startsWith(ProjectStackEnv.CONFIG_PREFIX) && !name.startsWith(ProjectStackEnv.CDKTF_PREFIX)) {
                        Files.createSymbolicLink(home.resolve(name), entry.toAbsolutePath());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot make the home of project stack " + projectStack.id() + " in " + home, e);
        }
        return home;
    }

    /**
     * Removes the links of a home that was made before, never what they point to, and nothing that is not a link.
     */
    private static void deleteLinks(Path home) throws IOException {
        if (!Files.isDirectory(home)) {
            return;
        }
        try (Stream<Path> entries = Files.list(home)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (Files.isSymbolicLink(entry)) {
                    Files.delete(entry);
                }
            }
        }
    }
}
