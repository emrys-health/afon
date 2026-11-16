package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.project.AfonProject;
import health.emrys.afon.project.FileProjectProvider;
import health.emrys.afon.project.ProjectEnv;
import health.emrys.afon.project.ProjectNames;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static health.emrys.afon.ops.project.ProjectTemplates.lower;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The config of a project is read by two readers that must agree: the scripts, which a shell sources, evaluating {@code ${NAME}} where it reads a
 * line, and the Java side, which resolves the references once every file is read.  A property set in terms of one that the project overrides further down
 * is where they would differ, so every property of the config that {@code configure} makes is read both ways here, on every cloud, as the launcher
 * does: with {@code scripts/config/afon-source.sh}.
 */
class ProjectConfigShellTest {

    private static final Path AFON_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern PROPERTY = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*)=");

    @TempDir
    Path home;

    /** An env config as the deployer's, with properties set in terms of others, across files, as the templates and the real configs have. */
    private Path envConfig(AfonCloud cloud) throws IOException {
        Path dir = Files.createDirectories(home.resolve("config-dev"));
        String c = cloud.name();
        Files.writeString(dir.resolve("afon.properties"),
                          "ENV_NAME=dev\nCLUSTER_NAME=${ENV_NAME}-cluster\n" + ProjectEnvAccounts.ACCOUNTS);
        String own = switch (cloud) {
            case AZURE -> "AZURE_STORAGE_ACCOUNT=${ENV_NAME}afonstorage\nAZURE_GITEA_BUCKET=${AZURE_STORAGE_ACCOUNT}-gitea\n"
                          + "AZURE_POLARIS_LOG_BUCKET=${AZURE_STORAGE_ACCOUNT}-polaris-logs\nAZURE_KEY_VAULT_NAME=${ENV_NAME}-afon-key-vault\n";
            default -> c + "_GITEA_BUCKET=${ENV_NAME}-gitea\n" + c + "_POLARIS_LOG_BUCKET=${" + c + "_GITEA_BUCKET}-logs\n";
        };
        // in terms of a property of another file, which the project overrides
        Files.writeString(dir.resolve("afon-" + lower(cloud) + ".properties"),
                          own + c + "_RESOURCE_PREFIX=${CLUSTER_NAME}-res\n" + c + "_JUPYTER_DOMAIN_NAME=" + lower(cloud) + "-jupyter.${ENV_NAME}.click\n");
        Files.writeString(dir.resolve("catalogs.properties"), "");
        Files.writeString(dir.resolve("catalogs-" + lower(cloud) + ".properties"),
                          c + "_CATALOG_IDS=" + lower(cloud) + "\n" + c + "_BUCKET_NAME_" + lower(cloud) + "=${ENV_NAME}-bucket\n");
        return dir;
    }

    /** The names of the properties that the files of the config set. */
    private static Set<String> namesIn(Path config) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (var files = Files.list(config)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted()::iterator) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Matcher property = PROPERTY.matcher(line);
                    if (property.find()) {
                        names.add(property.group(1));
                    }
                }
            }
        }
        return names;
    }

    /** The value of each property, as a shell has it after sourcing the config as {@code afon-source.sh} does. */
    private Map<String, String> asTheScriptsSeeIt(AfonCloud cloud, Path config, Set<String> names) throws Exception {
        Path afonHome = Files.createDirectories(home.resolve("shell-home"));
        Files.deleteIfExists(afonHome.resolve("config"));
        Files.createSymbolicLink(afonHome.resolve("config"), config);
        List<String> command = new ArrayList<>(List.of("bash", "-c",
            "source \"$AFON_ROOT/scripts/config/afon-source.sh\" \"$1\" > /dev/null 2>&1; shift; for name in \"$@\"; do printf '%s\\0%s\\0' \"$name\" \"${!name-}\"; done",
            "bash", lower(cloud)));
        command.addAll(names);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
        builder.environment().put("AFON_ROOT", AFON_ROOT.toString());
        builder.environment().put("AFON_HOME", afonHome.toString());
        // nothing of the machine's own config leaks in: a name the files set that the machine has too would be the machine's if a file did not set it
        names.forEach(name -> builder.environment().remove(name));
        Process process = builder.start();
        byte[] out = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        String[] fields = new String(out, StandardCharsets.UTF_8).split("\0", -1);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            values.put(fields[i], fields[i + 1]);
        }
        return values;
    }

    private Path configured(AfonCloud cloud, String yaml) throws IOException {
        Path file = Files.writeString(home.resolve("project.yaml"), yaml);
        AfonProject project = FileProjectProvider.parse(yaml);
        return ProjectEnv.configure(project, envConfig(cloud), home, file);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theScriptsAndTheJavaSideSeeTheSameConfigOfAProject(AfonCloud cloud) throws Exception {
        Assumptions.assumeTrue(new ProcessBuilder("bash", "-c", "true").start().waitFor() == 0);
        // no Git bucket of its own, so that the env's, set in terms of other properties, is the one the project's is derived from
        Path config = configured(cloud, ProjectTemplates.example(cloud).replace("    gitBucket: xyz-yyyy-nnnn-gitea\n", ""));
        Set<String> names = namesIn(config);
        assertThat(names).contains("CLUSTER_NAME", cloud.name() + "_RESOURCE_PREFIX", cloud.name() + "_JUPYTER_DOMAIN_NAME", "PROJECT_ID");

        Map<String, String> shell = asTheScriptsSeeIt(cloud, config, names);
        Properties java = new FilePropertyProvider(config.toString(), List.of("afon", "catalogs")).getProperties();

        // every property, which is the point: the ones that a project overrides, and the ones set in terms of them
        for (String name : names) {
            assertThat(shell.get(name)).as("%s, as the scripts see it, and as Java does", name).isEqualTo(java.getProperty(name, ""));
        }
        // and the values are the project's, not the env's
        String id = "xyz-yyyy-nnnn";
        assertThat(shell).containsEntry("PROJECT_ID", id).containsEntry("CLUSTER_NAME", id + "-cluster")
                         .containsEntry(cloud.name() + "_RESOURCE_PREFIX", id + "-cluster-res");
        if (cloud == AfonCloud.AZURE) {
            String account = "afonstorage" + ProjectNames.token(cloud, "sub-1", id);
            assertThat(shell).containsEntry("AZURE_STORAGE_ACCOUNT", account)
                             // set in terms of the account, which is the project's by the time it is read
                             .containsEntry("AZURE_GITEA_BUCKET", account + "-gitea")
                             .containsEntry("AZURE_POLARIS_LOG_BUCKET", account + "-polaris-logs");
        }
    }
}
