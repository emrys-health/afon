package health.emrys.afon.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Afon home that a project's commands run in.  The scripts and commands look for the config in {@code $AFON_HOME/config}, which an env
 * names by switching a symlink for everyone, so a project gets a home of its own instead: {@code <afon-home>/projects/<project>/home}, whose
 * {@code config} and {@code cdktf} are those of the project, {@code config-<project>} and {@code cdktf-<project>}, and whose other entries are
 * those of the Afon home (the CLI, logs, keys), so that running one project never switches the config of another, nor the one the deployer is
 * working in.
 */
public final class ProjectHome {

    public static final String HOME_DIR = "home";

    /**
     * What the Afon home has that is not shared with a project's home: the config and cdktf that are active, and the envs.  The projects are,
     * so that a command in the project's home finds its project file and its keys.
     */
    private static final Set<String> NOT_SHARED = Set.of("config", "cdktf");

    private ProjectHome() {
    }

    /**
     * @return the home of the project, {@code <afon-home>/projects/<project>/home}
     */
    public static Path dir(Path afonHome, AfonProject project) {
        return FileProjectProvider.projectDir(afonHome, project.id().value()).resolve(HOME_DIR);
    }

    /**
     * Makes the home of the project, replacing the one it had: links to the config and cdktf of the project and to the other entries of the
     * Afon home.
     *
     * @return the home
     * @throws IllegalStateException if the project has no config env yet, ie it is not configured, see {@link ProjectEnv#configure}
     * @throws UncheckedIOException  if the links cannot be made
     */
    public static Path prepare(Path afonHome, AfonProject project) {
        Path config = ProjectEnv.configDir(afonHome, project);
        Path cdktf = ProjectEnv.cdktfDir(afonHome, project);
        if (!Files.isDirectory(config) || !Files.isDirectory(cdktf)) {
            throw new IllegalStateException(String.format(
                "Project %s is not configured: %s or %s is missing; run manage-project configure %s", project.id(), config, cdktf, project.id()));
        }
        Path home = dir(afonHome, project);
        try {
            deleteLinks(home);
            Files.createDirectories(home);
            Files.createSymbolicLink(home.resolve("config"), config.toAbsolutePath());
            Files.createSymbolicLink(home.resolve("cdktf"), cdktf.toAbsolutePath());
            try (Stream<Path> entries = Files.list(afonHome)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    String name = entry.getFileName().toString();
                    if (!NOT_SHARED.contains(name) && !name.startsWith(ProjectEnv.CONFIG_PREFIX) && !name.startsWith(ProjectEnv.CDKTF_PREFIX)) {
                        Files.createSymbolicLink(home.resolve(name), entry.toAbsolutePath());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot make the home of project " + project.id() + " in " + home, e);
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
