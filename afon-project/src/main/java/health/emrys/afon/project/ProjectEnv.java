package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.ConfigUtils;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonProperty.*;

/**
 * The env of a project: its config, {@code config-<project>}, and its cdktf, {@code cdktf-<project>}, both in the Afon home, as an env
 * is for any deployment.  They keep the stacks, tofu state and kubeconfigs of the project apart from those of every other env and
 * project, and stay as the record of its deployments, see {@link ProjectHome} for running in it.
 * <p>
 * The config is made from the config of the deployer's env that the project names, which holds what {@code project.yaml} does not (the account, node pools,
 * secrets), with the properties derived from {@code project.yaml} written over it: the scripts source the properties files, and the
 * Java side reads them, so both see one config, the later line of a file winning.  Configuring again replaces the files, so the config
 * of a project is always what its {@code project.yaml} and its env say.
 */
public final class ProjectEnv {

    public static final String CONFIG_PREFIX = "config-";
    public static final String CDKTF_PREFIX = "cdktf-";

    static final String COMMON_FILE = "afon.properties";
    static final String BLOCK_HEADER = "# --- Derived from project.yaml by manage-project: do not edit, configure it again ---";

    /**
     * What a properties file may be given, as it is sourced by a shell: no space, quote, {@code $}, {@code ;} or the like, but the {@code ,} of a node pool.
     */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:/@,\\[\\]+-]+");

    private ProjectEnv() {
    }

    /**
     * @return the config dir of the deployer's env, {@code <afon-home>/config-<env>}, which a project is made from
     * @throws IllegalArgumentException if {@code env} is not the name of an env, ie it could name a path
     */
    public static @NonNull Path envConfigDir(Path afonHome, String env) {
        if (!env.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(String.format("'%s' is not the name of an env, eg dev for config-dev", env));
        }
        return afonHome.resolve(CONFIG_PREFIX + env);
    }

    /**
     * @return the config dir of the project, {@code <afon-home>/config-<project>}
     */
    public static @NonNull Path configDir(Path afonHome, AfonProject project) {
        return afonHome.resolve(CONFIG_PREFIX + project.id());
    }

    /**
     * @return the cdktf dir of the project, {@code <afon-home>/cdktf-<project>}
     */
    public static @NonNull Path cdktfDir(Path afonHome, AfonProject project) {
        return afonHome.resolve(CDKTF_PREFIX + project.id());
    }

    /**
     * @return the properties derived from the project, by the name of the properties file they go in, {@code afon.properties} for those
     * of every cloud and {@code afon-<cloud>.properties} for those of the project's cloud
     * @throws ProjectException if a value cannot be written to a properties file that a shell sources
     */
    public static Map<String, Map<String, String>> derive(AfonProject project) {
        return derive(project, null);
    }

    /**
     * As {@link #derive(AfonProject)}, with the kubeconfig of the identity project's cluster, which a TRE project's stacks configure its
     * Keycloak through, or {@code null} if it names none.
     */
    public static Map<String, Map<String, String>> derive(AfonProject project, Path identityKubeconfig) {
        AfonCloud cloud = project.cloud();
        String prefix = cloud.name() + "_";
        Map<String, String> common = new LinkedHashMap<>();
        Map<String, String> cloudSpecific = new LinkedHashMap<>();
        common.put(PROJECT_ID.getName(), project.id().value());
        if (identityKubeconfig != null) {
            common.put(KEYCLOAK_KUBECONFIG.getName(), identityKubeconfig.toString());
        }
        if (project.egressIp() != null) {
            cloudSpecific.put(prefix + "EGRESS_IP", project.egressIp());
        }
        if (!project.nodePool().isEmpty()) {
            String property = switch (cloud) {
                case AZURE -> AZURE_AKS_NODE_POOL.getName();
                case GCP -> GCP_GKE_NODE_POOL.getName();
                case AWS -> throw new ProjectException(FileProjectProvider.FILE, List.of("cluster.nodePool is not available on aws"));
            };
            cloudSpecific.put(property, project.nodePool().entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                                              .collect(Collectors.joining(",")));
        }
        if (project instanceof IdentityProject identity) {
            // it deploys the identity, so it uses no other: nothing of the clients, the Providers or the TRE, and no external identity
            if (identity.domain() != null) {
                cloudSpecific.put(prefix + "OIDC_DOMAIN_NAME", identity.domain());
            }
        } else {
            deriveTre((TreProject) project, prefix, common, cloudSpecific);
        }

        List<String> problems = new ArrayList<>();
        for (Map<String, String> properties : List.of(common, cloudSpecific)) {
            properties.forEach((name, value) -> {
                if (!SAFE_VALUE.matcher(value).matches()) {
                    problems.add(String.format("%s '%s' has a character that a properties file sourced by a shell cannot hold", name, value));
                }
            });
        }
        if (!problems.isEmpty()) {
            throw new ProjectException(FileProjectProvider.FILE, problems);
        }
        Map<String, Map<String, String>> byFile = new LinkedHashMap<>();
        byFile.put(COMMON_FILE, common);
        byFile.put("afon-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", cloudSpecific);
        return byFile;
    }

    private static void deriveTre(TreProject project, String prefix, Map<String, String> common, Map<String, String> cloudSpecific) {
        TreProject.Identity identity = project.identity();
        common.put(OIDC_ISSUER_URL.getName(), identity.issuerUrl());
        common.put(OIDC_AUDIENCE.getName(), identity.treClient());
        if (identity.gitClient() != null) {
            common.put(OIDC_GIT_AUDIENCE.getName(), identity.gitClient());
        }
        common.put(OIDC_ACR.getName(), identity.acr());
        common.put(OIDC_PROJECT_CLAIM.getName(), identity.projectClaim());
        common.put(OIDC_USER_CLAIM.getName(), identity.userClaim());
        if (project.git() != null) {
            common.put(GIT_DOMAIN_NAME.getName(), project.git().domain());
        }
        if (project.tre().teardown() != null) {
            common.put(TRE_TEARDOWN_TIME.getName(), project.tre().teardown());
        }
        common.put(TRE_TEARDOWN_TIME_ZONE.getName(), project.tre().timeZone());

        cloudSpecific.put(prefix + "KEYCLOAK_EXTERNAL", "true");
        cloudSpecific.put(prefix + "AUDIT_BUCKET", project.create().auditBucket());
        if (project.create().gitBucket() != null) {
            cloudSpecific.put(prefix + "GITEA_BUCKET", project.create().gitBucket());
        }
        project.tre().domains().forEach((property, domain) -> (property.startsWith(prefix) ? cloudSpecific : common).put(property, domain));
    }

    /**
     * Configures the project: makes its config env, replacing what an earlier configure made: the files of the config dir of the env it is made from, with the derived
     * properties written at the end of the properties files, and {@code project.yaml} itself, as the record of what was deployed.
     * The cdktf dir is made, empty, if it is absent.
     *
     * @param envConfig   the config dir of the env the project is made from, {@code config-<env>}, which holds what the project file does not
     * @param projectFile the project file, which is copied into the config env
     * @return the config dir of the project
     * @throws IllegalArgumentException if the config dir is not a dir, or is that of the project itself
     * @throws UncheckedIOException     if a file cannot be read or written
     */
    public static Path configure(AfonProject project, Path envConfig, Path afonHome, Path projectFile) {
        if (!Files.isDirectory(envConfig)) {
            throw new IllegalArgumentException("The config dir " + envConfig + " is not a directory");
        }
        Map<String, Map<String, String>> derived = derive(project, identityKubeconfig(project, afonHome));
        Path target = configDir(afonHome, project);
        if (envConfig.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("The config dir " + envConfig + " is that of the project itself: name another env for it to be made from");
        }
        try {
            copyTree(envConfig, target);
            for (Map.Entry<String, Map<String, String>> file : derived.entrySet()) {
                append(target.resolve(file.getKey()), file.getValue());
            }
            Files.copy(projectFile, target.resolve(FileProjectProvider.FILE), StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(cdktfDir(afonHome, project));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot configure project " + project.id() + ": the config env is " + target, e);
        }
        return target;
    }

    /**
     * @return the kubeconfig of the cluster of the identity project that the project names, where its stack writes it, or {@code null} if it names none
     * @throws ProjectException if the identity project has no file, or is not an identity project
     */
    static Path identityKubeconfig(AfonProject project, Path afonHome) {
        if (!(project instanceof TreProject tre) || tre.identity().project() == null) {
            return null;
        }
        String id = tre.identity().project();
        FileProjectProvider provider = FileProjectProvider.forProject(afonHome, id);
        if (!provider.exists()) {
            throw new ProjectException(FileProjectProvider.FILE, List.of(String.format(
                "identity.project names %s, which has no file %s: make it first, as the project that deploys the central identity", id, provider.file())));
        }
        if (!(provider.getProject() instanceof IdentityProject identity)) {
            throw new ProjectException(FileProjectProvider.FILE, List.of(String.format(
                "identity.project names %s, which is not an identity project", id)));
        }
        return cdktfDir(afonHome, identity).resolve("kube").resolve(identity.stack() + ".config");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path source : (Iterable<Path>) paths::iterator) {
                Path destination = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void append(Path file, Map<String, String> properties) throws IOException {
        StringBuilder block = new StringBuilder();
        if (Files.exists(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            block.append(existing.endsWith("\n") || existing.isEmpty() ? "" : "\n");
        }
        block.append('\n').append(BLOCK_HEADER).append('\n');
        properties.forEach((name, value) -> block.append(name).append('=').append(value).append('\n'));
        Files.writeString(file, block, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * @return the afon home, {@link ConfigUtils#afonHome()}, as a path
     */
    public static Path defaultAfonHome() {
        return Path.of(ConfigUtils.afonHome());
    }
}
