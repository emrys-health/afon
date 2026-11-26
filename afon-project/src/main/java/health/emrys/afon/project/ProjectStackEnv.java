package health.emrys.afon.project;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.FilePropertyProvider;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonProperty.*;

/**
 * The env of a project stack: its config, {@code config-<project-stack>}, and its cdktf, {@code cdktf-<project-stack>}, both in the Afon home, as an env
 * is for any deployment.  They keep the stacks, tofu state and kubeconfigs of the project stack apart from those of every other env and
 * project stack, and stay as the record of its deployments, see {@link ProjectStackHome} for running in it.
 * <p>
 * The config is made from the config of the deployer's env that the project stack names, which holds what {@code project.yaml} does not (the account, node pools,
 * secrets), with the properties derived from {@code project.yaml} written over it: the scripts source the properties files, and the
 * Java side reads them, so both see one config, the later line of a file winning.  Configuring again replaces the files, so the config
 * of a project stack is always what its {@code project.yaml} and its env say.
 */
public final class ProjectStackEnv {

    public static final String CONFIG_PREFIX = "config-";
    public static final String CDKTF_PREFIX = "cdktf-";

    static final String COMMON_FILE = "afon.properties";
    static final String BLOCK_HEADER = "# --- Derived from project.yaml by manage-project-stack: do not edit, configure it again ---";

    /**
     * What a properties file may be given, as it is sourced by a shell: no space, quote, {@code $}, {@code ;} or the like, but the {@code ,} of a node pool and the {@code ?} and {@code =} of the parameters of a database URL.
     */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:/@,\\[\\]+?=-]+");

    private ProjectStackEnv() {
    }

    /**
     * @return the config dir of the deployer's env, {@code <afon-home>/config-<env>}, which a project stack is made from.  In the home of a project stack, which
     * links no env, it is that of the Afon home that the project stack's home is made from, found through its {@code projects} link, as a project stack that
     * names another's, eg a TRE that names a Data Provider, is looked up in the config of that one's env
     * @throws IllegalArgumentException if {@code env} is not the name of an env, ie it could name a path
     */
    public static @NonNull Path envConfigDir(Path afonHome, String env) {
        if (!env.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(String.format("'%s' is not the name of an env, eg dev for config-dev", env));
        }
        Path dir = afonHome.resolve(CONFIG_PREFIX + env);
        if (!Files.isDirectory(dir)) {
            Path shared = sharedAfonHome(afonHome);
            if (shared != null && Files.isDirectory(shared.resolve(CONFIG_PREFIX + env))) {
                return shared.resolve(CONFIG_PREFIX + env);
            }
        }
        return dir;
    }

    /**
     * @return the Afon home that the home of a project stack is made from, which is where its {@code projects} entry is linked from, or {@code null} if
     * the home is not a project stack's, ie its {@code projects} entry is not a link
     */
    private static Path sharedAfonHome(Path afonHome) {
        Path projectStacks = afonHome.resolve(FileProjectStackProvider.PROJECTS_DIR);
        try {
            return Files.isSymbolicLink(projectStacks) ? projectStacks.toRealPath().getParent() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The stacks of a project stack read the copy of its file that {@code configure} makes in its config, not the file: a file changed since, eg a member added to
     * it, is not what they deploy.
     *
     * @return whether the project stack's file was changed after {@code configure} made the copy of it; not if there is no copy, which is another matter, that
     * the project stack is not configured
     */
    public static boolean fileIsNewerThanItsCopy(Path afonHome, ProjectStack projectStack, Path projectFile) {
        Path copy = configDir(afonHome, projectStack).resolve(FileProjectStackProvider.FILE);
        try {
            return Files.isRegularFile(copy) && Files.isRegularFile(projectFile)
                   && Files.getLastModifiedTime(projectFile).compareTo(Files.getLastModifiedTime(copy)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return the config dir of the project stack, {@code <afon-home>/config-<project-stack>}
     */
    public static @NonNull Path configDir(Path afonHome, ProjectStack projectStack) {
        return afonHome.resolve(CONFIG_PREFIX + projectStack.id());
    }

    /**
     * @return the cdktf dir of the project stack, {@code <afon-home>/cdktf-<project-stack>}
     */
    public static @NonNull Path cdktfDir(Path afonHome, ProjectStack projectStack) {
        return afonHome.resolve(CDKTF_PREFIX + projectStack.id());
    }

    /**
     * @return the properties derived from the project stack, by the name of the properties file they go in, {@code afon.properties} for those
     * of every cloud and {@code afon-<cloud>.properties} for those of the project stack's cloud
     * @throws ProjectStackException if a value cannot be written to a properties file that a shell sources
     */
    public static Map<String, Map<String, String>> derive(ProjectStack projectStack) {
        return derive(projectStack, null);
    }

    /**
     * As {@link #derive(ProjectStack)}, with the kubeconfig of the identity project stack's cluster, which a TRE project stack's stacks configure its
     * Keycloak through, or {@code null} if it names none.
     */
    public static Map<String, Map<String, String>> derive(ProjectStack projectStack, Path identityKubeconfig) {
        return derive(projectStack, identityKubeconfig, null);
    }

    /**
     * As {@link #derive(ProjectStack, Path)}, with the names of the project stack's resources, which depend on the config the project stack is made from, if
     * it is given, see {@link ProjectStackNames}.
     *
     * @param env the config the project stack is made from, with its references resolved, or {@code null} for no names
     */
    public static Map<String, Map<String, String>> derive(ProjectStack projectStack, Path identityKubeconfig, Properties env) {
        return derive(projectStack, identityKubeconfig, null, env);
    }

    /**
     * As {@link #derive(ProjectStack, Path, Properties)}, with the cloud of the identity project stack's cluster, which differs from the project stack's when the
     * identity is on another cloud, eg a Data Provider on Azure that uses an identity on AWS: the kubectl context of the port-forward to Keycloak is then
     * checked to be of that cloud.
     *
     * @param identityCloud the cloud of the identity project stack, or {@code null} if it names none
     */
    public static Map<String, Map<String, String>> derive(ProjectStack projectStack, Path identityKubeconfig, AfonCloud identityCloud, Properties env) {
        AfonCloud cloud = projectStack.cloud();
        String prefix = cloud.name() + "_";
        Map<String, String> common = new LinkedHashMap<>();
        Map<String, String> cloudSpecific = new LinkedHashMap<>();
        common.put(PROJECT_ID.getName(), projectStack.id().value());
        if (identityKubeconfig != null) {
            common.put(KEYCLOAK_KUBECONFIG.getName(), identityKubeconfig.toString());
            if (identityCloud != null && identityCloud != cloud) {
                common.put(KEYCLOAK_CLOUD.getName(), identityCloud.toString().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (projectStack.egressIp() != null) {
            cloudSpecific.put(prefix + "EGRESS_IP", projectStack.egressIp());
        }
        if (!projectStack.nodePool().isEmpty()) {
            String property = switch (cloud) {
                case AZURE -> AZURE_AKS_NODE_POOL.getName();
                case GCP -> GCP_GKE_NODE_POOL.getName();
                case AWS -> throw new ProjectStackException(FileProjectStackProvider.FILE, List.of("cluster.nodePool is not available on aws"));
            };
            cloudSpecific.put(property, projectStack.nodePool().entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                                              .collect(Collectors.joining(",")));
        }
        if (projectStack instanceof IdentityProjectStack identity) {
            // it deploys the identity, so it uses no other: nothing of the clients, the Data Providers or the TRE, and no external identity
            if (identity.domain() != null) {
                cloudSpecific.put(prefix + "OIDC_DOMAIN_NAME", identity.domain());
            }
        } else if (projectStack instanceof DataProjectStack data) {
            // a Data Provider deploys its Polaris, with nothing of the clients, the Data Providers or the TRE, and it trusts the central identity, which
            // is at the host of its issuer: that is its OIDC domain, as the config reads the issuer as being at the OIDC domain of the cloud
            if (data.domain() != null) {
                cloudSpecific.put(prefix + "POLARIS_DOMAIN_NAME", data.domain());
            }
            DataProjectStack.Identity identity = data.identity();
            common.put(OIDC_ISSUER_URL.getName(), identity.issuerUrl());
            common.put(OIDC_ACR.getName(), identity.acr());
            common.put(OIDC_AUDIENCE.getName(), identity.audience());
            String issuerHost = issuerHost(identity.issuerUrl());
            if (issuerHost != null) {
                cloudSpecific.put(prefix + "OIDC_DOMAIN_NAME", issuerHost);
            }
        } else {
            deriveTre((TreProjectStack) projectStack, prefix, common, cloudSpecific);
        }

        Map<String, Map<String, String>> byFile = new LinkedHashMap<>();
        byFile.put(COMMON_FILE, common);
        byFile.put("afon-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", cloudSpecific);
        if (projectStack instanceof TreProjectStack tre) {
            byFile.putAll(deriveDataProviders(tre, env));
        }
        if (projectStack instanceof DataProjectStack) {
            // a Data Provider holds its own catalogs and no others: the stack makes those of every cloud that the config lists, so those of the other
            // clouds, which the env lists for its own stacks, are emptied (the project stack's cloud has its own list, from the names of its resources)
            for (AfonCloud other : AfonCloud.values()) {
                if (other != cloud) {
                    byFile.computeIfAbsent("catalogs-" + other.toString().toLowerCase(Locale.ROOT) + ".properties", file -> new LinkedHashMap<>())
                          .put(other.toString().toUpperCase(Locale.ROOT) + "_CATALOG_IDS", "");
                }
            }
        }
        if (env != null) {
            // the names of the project stack's resources go first, so that what the file says is not overridden by them
            ProjectStackNames.derive(projectStack, env, projectStack.env()).forEach((file, names) -> {
                Map<String, String> explicit = byFile.getOrDefault(file, Map.of());
                Map<String, String> merged = new LinkedHashMap<>(names);
                merged.putAll(explicit);
                byFile.put(file, merged);
            });
        }
        List<String> problems = new ArrayList<>();
        byFile.values().forEach(properties -> properties.forEach((name, value) -> {
            if (!value.isEmpty() && !SAFE_VALUE.matcher(value).matches()) {
                problems.add(String.format("%s '%s' has a character that a properties file sourced by a shell cannot hold", name, value));
            }
        }));
        if (!problems.isEmpty()) {
            throw new ProjectStackException(FileProjectStackProvider.FILE, problems);
        }
        return byFile;
    }

    /** @return the host of an issuer URL, or {@code null} if it has none */
    private static String issuerHost(String issuerUrl) {
        try {
            String host = java.net.URI.create(issuerUrl).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void deriveTre(TreProjectStack projectStack, String prefix, Map<String, String> common, Map<String, String> cloudSpecific) {
        TreProjectStack.Identity identity = projectStack.identity();
        common.put(OIDC_ISSUER_URL.getName(), identity.issuerUrl());
        common.put(OIDC_AUDIENCE.getName(), identity.treClient());
        // the TRE signs its members in with the project stack's client, not the env's: its tokens carry the project stack's audience, claim and acr.  The id and the
        // secret of the client are written together, see configure
        if (identity.gitClient() != null) {
            common.put(OIDC_GIT_AUDIENCE.getName(), identity.gitClient());
        }
        common.put(OIDC_ACR.getName(), identity.acr());
        common.put(OIDC_PROJECT_CLAIM.getName(), identity.projectClaim());
        projectStack.smokeMember().ifPresent(member -> common.put(SMOKE_MEMBER.getName(), member.email()));
        common.put(OIDC_USER_CLAIM.getName(), identity.userClaim());
        if (projectStack.git() != null) {
            common.put(GIT_DOMAIN_NAME.getName(), projectStack.git().domain());
        }
        if (projectStack.tre().teardown() != null) {
            common.put(TRE_TEARDOWN_TIME.getName(), projectStack.tre().teardown());
        }
        common.put(TRE_TEARDOWN_TIME_ZONE.getName(), projectStack.tre().timeZone());

        cloudSpecific.put(prefix + "KEYCLOAK_EXTERNAL", "true");
        // the TRE's OIDC domain is the identity's, which the issuer names: the config reads the issuer as being at the OIDC domain of the cloud, and the
        // identity is not at the env's own, when it is deployed by a project stack, so it would otherwise be refused
        String issuerHost = issuerHost(identity.issuerUrl());
        if (issuerHost != null) {
            cloudSpecific.put(prefix + "OIDC_DOMAIN_NAME", issuerHost);
        }
        cloudSpecific.put(prefix + "AUDIT_BUCKET", projectStack.create().auditBucket());
        if (projectStack.create().gitBucket() != null) {
            cloudSpecific.put(prefix + "GITEA_BUCKET", projectStack.create().gitBucket());
        }
        projectStack.tre().domains().forEach((property, domain) -> (property.startsWith(prefix) ? cloudSpecific : common).put(property, domain));
    }

    /**
     * The catalogs of the Data Providers that the project stack names, as catalogs like any other, in the {@code catalogs-<cloud>.properties} of the cloud of each
     * one's storage, with the source {@link AfonCatalogs.Source#DATA_PROVIDER}: {@code <CLOUD>_CATALOG_IDS}, which keeps the ids that the config the project stack is
     * made from lists, and for each id {@code _CATALOG_NAME_<id>} (its name at the endpoint), {@code _CATALOG_TYPE_<id>} ({@code rest}, as it is an Iceberg REST
     * catalog that is reached), {@code _CATALOG_SOURCE_<id>}, {@code _CATALOG_URI_<id>}, {@code _CATALOG_AUDIENCE_<id>} and {@code _BUCKET_NAME_<id>} (its
     * storage, which is the Data Provider's).  The id is that of the entry, with its hyphens as underscores, for it names properties that a shell sources.
     * The scripts that make and delete the storage of a stack's catalogs skip those of this source.
     *
     * @param env the config the project stack is made from, or {@code null} for none
     */
    private static Map<String, Map<String, String>> deriveDataProviders(TreProjectStack projectStack, Properties env) {
        Map<String, Map<String, String>> byFile = new LinkedHashMap<>();
        Map<String, Set<String>> ids = new LinkedHashMap<>();
        for (TreProjectStack.DataProvider provider : projectStack.dataProviders()) {
            String prefix = provider.cloud().name() + "_";
            String file = "catalogs-" + provider.cloud().toString().toLowerCase(Locale.ROOT) + ".properties";
            Map<String, String> catalogs = byFile.computeIfAbsent(file, f -> new LinkedHashMap<>());
            Set<String> ofCloud = ids.computeIfAbsent(file, f -> {
                Set<String> existing = new LinkedHashSet<>();
                if (env != null) {
                    for (String id : env.getProperty(prefix + "CATALOG_IDS", "").split(",")) {
                        if (!id.isBlank()) {
                            existing.add(id.trim());
                        }
                    }
                }
                return existing;
            });
            String id = provider.id().replace('-', '_');
            if (!ofCloud.add(id)) {
                throw new ProjectStackException(FileProjectStackProvider.FILE, List.of(String.format(
                    "dataProviders: '%s' is the catalog id '%s', which another catalog of %s has already", provider.id(), id, provider.cloud())));
            }
            catalogs.put(prefix + "CATALOG_NAME_" + id, provider.catalogName());
            catalogs.put(prefix + "CATALOG_TYPE_" + id, "rest");
            catalogs.put(prefix + "CATALOG_SOURCE_" + id, AfonCatalogs.Source.DATA_PROVIDER.value());
            catalogs.put(prefix + "CATALOG_URI_" + id, provider.catalogUri());
            catalogs.put(prefix + "CATALOG_AUDIENCE_" + id, provider.audience());
            catalogs.put(prefix + "BUCKET_NAME_" + id, provider.bucket());
        }
        ids.forEach((file, ofCloud) -> {
            Map<String, String> catalogs = new LinkedHashMap<>();
            catalogs.put(file.substring("catalogs-".length(), file.length() - ".properties".length()).toUpperCase(Locale.ROOT) + "_CATALOG_IDS",
                         String.join(",", ofCloud));
            catalogs.putAll(byFile.get(file));
            byFile.put(file, catalogs);
        });
        return byFile;
    }

    /**
     * Configures the project stack: makes its config env, replacing what an earlier configure made: the files of the config dir of the env it is made from, with the derived
     * properties written at the end of the properties files, and {@code project.yaml} itself, as the record of what was deployed.
     * The cdktf dir is made, empty, if it is absent.
     *
     * @param envConfig   the config dir of the env the project stack is made from, {@code config-<env>}, which holds what the project stack file does not
     * @param projectFile the project stack file, which is copied into the config env
     * @return the config dir of the project stack
     * @throws IllegalArgumentException if the config dir is not a dir, or is that of the project stack itself
     * @throws UncheckedIOException     if a file cannot be read or written
     */
    public static Path configure(ProjectStack projectStack, Path envConfig, Path afonHome, Path projectFile) {
        if (!Files.isDirectory(envConfig)) {
            throw new IllegalArgumentException("The config dir " + envConfig + " is not a directory");
        }
        Path target = configDir(afonHome, projectStack);
        if (envConfig.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("The config dir " + envConfig + " is that of the project stack itself: name another env for it to be made from");
        }
        // the config the project stack is made from, with its references resolved, which the names of the project stack's resources depend on (the account)
        Properties envProperties = envProperties(envConfig);
        IdentityRef identityRef = identityRef(projectStack, afonHome);
        Map<String, Map<String, String>> derived = derive(projectStack, identityRef == null ? null : identityRef.kubeconfig(),
                                                          identityRef == null ? null : identityRef.cloud(), envProperties);
        if (projectStack instanceof DataProjectStack data) {
            admitTheTres(data, afonHome, envProperties, derived);
        }
        if (projectStack instanceof TreProjectStack tre) {
            // both in secret.properties, where the env has them and which is read after afon.properties, so that the env's client id does not override the project stack's
            Map<String, String> secrets = derived.computeIfAbsent("secret.properties", file -> new LinkedHashMap<>());
            secrets.put(OAUTH2_CLIENT_ID.getName(), tre.identity().treClient());
            secrets.put(OAUTH2_CLIENT_SECRET.getName(), clientSecretOf(tre, afonHome));
        }
        try {
            copyTree(envConfig, target);
            for (Map.Entry<String, Map<String, String>> file : derived.entrySet()) {
                append(target.resolve(file.getKey()), file.getValue());
            }
            reevaluateDependents(target, derived);
            Files.copy(projectFile, target.resolve(FileProjectStackProvider.FILE), StandardCopyOption.REPLACE_EXISTING);
            if (projectStack instanceof TreProjectStack tre && tre.dataProviders().stream().anyMatch(provider -> provider.projectStack() != null)) {
                writeResolvedProviders(tre, target.resolve(FileProjectStackProvider.FILE));
            }
            Files.createDirectories(cdktfDir(afonHome, projectStack));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot configure project stack " + projectStack.id() + ": the config env is " + target, e);
        }
        return target;
    }

    /**
     * @return the secret of the project stack's TRE client, which {@code project-secret} made in the project stack's {@code keys} dir, and which the identity is given
     * when the project stack is made in it: the TRE's JupyterHub and the gate in front of it sign members in with it
     * @throws ProjectStackException if there is none
     */
    static String clientSecretOf(TreProjectStack projectStack, Path afonHome) {
        Path file = FileProjectStackProvider.secretsDir(afonHome, projectStack.id().value()).resolve("tre.secret");
        try {
            String secret = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : "";
            if (secret.isEmpty()) {
                throw new ProjectStackException(FileProjectStackProvider.FILE, List.of(String.format(
                    "no secret for the TRE client %s: make it with afon-cli.sh project-secret %s tre, then configure the project stack again, as the config of the TRE has it",
                    projectStack.identity().treClient(), projectStack.id().value())));
            }
            if (!SAFE_VALUE.matcher(secret).matches()) {
                throw new ProjectStackException(FileProjectStackProvider.FILE, List.of(String.format(
                    "the secret in %s has a character that a properties file sourced by a shell cannot hold: make another with project-secret --force", file)));
            }
            return secret;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the secret of the TRE client " + projectStack.identity().treClient() + " in " + file, e);
        }
    }

    /**
     * A Data Provider's Polaris is reached by the TREs that read its catalogs, so it admits their egress IPs, in addition to the addresses that its config
     * already allows, in {@code <CLOUD>_POLARIS_ALLOWED_CIDRS}, which the deploy and {@code polaris-whitelist} both restrict its ingress to.  With no TRE naming
     * it, the property is left as the config has it: a Polaris with none is open, as it is for any other stack, and is guarded by its token checks alone.
     */
    private static void admitTheTres(DataProjectStack data, Path afonHome, Properties env, Map<String, Map<String, String>> derived) {
        List<String> egress = TreEgress.of(afonHome, data);
        if (egress.isEmpty()) {
            return;
        }
        String property = data.cloud().name() + "_POLARIS_ALLOWED_CIDRS";
        Set<String> cidrs = new LinkedHashSet<>();
        for (String configured : env.getProperty(property, "").split(",")) {
            if (!configured.isBlank()) {
                cidrs.add(configured.trim());
            }
        }
        egress.forEach(ip -> cidrs.add(ip + "/32"));
        derived.computeIfAbsent("afon-" + data.cloud().toString().toLowerCase(Locale.ROOT) + ".properties", file -> new LinkedHashMap<>())
               .put(property, String.join(",", cidrs));
    }

    /**
     * Writes the catalogs that the project stack names of the Data Providers' project stacks as they were resolved, each in full, in the copy of the project stack
     * file that the config holds, as the record of what was deployed: the file is read where the Data Providers' project stacks are not looked up, ie by the
     * commands that run in the project stack's home, which then see what was deployed, not what the Data Providers' files say now.  The comments of the file
     * are lost in the copy.
     */
    private static void writeResolvedProviders(TreProjectStack projectStack, Path copy) throws IOException {
        Map<String, Object> root = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()))
            .load(Files.readString(copy, StandardCharsets.UTF_8));
        List<Map<String, Object>> dataProviders = new ArrayList<>();
        for (TreProjectStack.DataProvider provider : projectStack.dataProviders()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", provider.id());
            entry.put("catalogUri", provider.catalogUri());
            entry.put("catalogName", provider.catalogName());
            entry.put("audience", provider.audience());
            entry.put("bucket", provider.bucket());
            entry.put("cloud", provider.cloud().toString().toLowerCase(java.util.Locale.ROOT));
            dataProviders.add(entry);
        }
        root.put("dataProviders", new LinkedHashMap<>(Map.of("defined", dataProviders)));
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        Files.writeString(copy, "# The dataProviders are the catalogs that the project stack names of its Data Providers' project stacks, resolved by configure.\n"
                                + new org.yaml.snakeyaml.Yaml(options).dump(root), StandardCharsets.UTF_8);
    }

    /**
     * The identity project stack that a project stack names: where the kubeconfig of its cluster is, and its cloud.
     */
    record IdentityRef(Path kubeconfig, AfonCloud cloud) {
    }

    /**
     * @return the cluster of the identity project stack that the project stack names, where its stack writes the kubeconfig, or {@code null} if it names none
     * @throws ProjectStackException if the identity project stack has no file, or is not an identity project stack
     */
    static IdentityRef identityRef(ProjectStack projectStack, Path afonHome) {
        String id = projectStack.identityProjectStack();
        if (id == null) {
            return null;
        }
        FileProjectStackProvider provider = FileProjectStackProvider.forProjectStack(afonHome, id);
        if (!provider.exists()) {
            throw new ProjectStackException(FileProjectStackProvider.FILE, List.of(String.format(
                "identity.projectStack names %s, which has no file %s: make it first, as the project stack that deploys the central identity", id, provider.file())));
        }
        if (!(provider.getProjectStack() instanceof IdentityProjectStack identity)) {
            throw new ProjectStackException(FileProjectStackProvider.FILE, List.of(String.format(
                "identity.projectStack names %s, which is not an identity project stack", id)));
        }
        return new IdentityRef(cdktfDir(afonHome, identity).resolve("kube").resolve(identity.stack() + ".config"), identity.cloud());
    }

    /**
     * @return the properties of the config dir that the names of a project stack's resources are made from, with their references resolved
     */
    public static Properties envProperties(Path envConfig) {
        Properties properties = new FilePropertyProvider(envConfig.toString(), List.of("afon", "catalogs"), true).getProperties();
        // the database of the env's Keycloak, which is a secret, from which the identity project stack names a database of its own
        try {
            String url = new FilePropertyProvider(envConfig.toString(), List.of("afon", "secret"), true).getValue(AfonProperty.KEYCLOAK_DB_URL, "");
            if (url != null && !url.isBlank()) {
                properties.setProperty(AfonProperty.KEYCLOAK_DB_URL.getName(), url.trim());
            }
        } catch (RuntimeException e) {
            // an env with no secrets, or secrets that do not resolve, has no database to derive one from
        }
        return properties;
    }

    /** A line of a properties file that sets a property in terms of others, eg {@code AZURE_GITEA_BUCKET=${AZURE_STORAGE_ACCOUNT}-gitea}. */
    private static final Pattern DEPENDENT = Pattern.compile("^([A-Za-z0-9_]+)=(.*\\$\\{([A-Za-z0-9_.]+)}.*)$");

    /**
     * A shell sourcing a properties file evaluates {@code ${NAME}} where it reads it, so a property set in terms of one that the derived block
     * overrides, further down, would keep the value of the env, while the Java side, which resolves the references after reading it all, would
     * see the new one.  Such a line is therefore written again, after the derived block, until none depends on a derived property.
     */
    private static void reevaluateDependents(Path configDir, Map<String, Map<String, String>> derived) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        derived.values().forEach(properties -> names.addAll(properties.keySet()));
        Map<String, List<String>> repeated = new LinkedHashMap<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String file : derived.keySet()) {
                Path path = configDir.resolve(file);
                if (!Files.exists(path)) {
                    continue;
                }
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    Matcher dependent = DEPENDENT.matcher(line);
                    if (dependent.matches() && !derived.getOrDefault(file, Map.of()).containsKey(dependent.group(1))
                        && Pattern.compile("\\$\\{(" + String.join("|", names) + ")}").matcher(dependent.group(2)).find()
                        && !repeated.computeIfAbsent(file, f -> new ArrayList<>()).contains(line)) {
                        repeated.get(file).add(line);
                        grew |= names.add(dependent.group(1));
                    }
                }
            }
        }
        for (Map.Entry<String, List<String>> file : repeated.entrySet()) {
            Files.writeString(configDir.resolve(file.getKey()), "# --- Set in terms of the above, so evaluated again after them ---\n"
                                                                  + String.join("\n", file.getValue()) + "\n",
                              StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
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
        Map<String, String> absent = properties;
        StringBuilder block = new StringBuilder();
        if (Files.exists(file)) {
            // what the env has of a property that is derived is replaced where it is, not left above a new one: a file with two lines for a key is read as
            // the last, which is not what it says, and a line that is set in terms of the property, ${NAME}, which the shell evaluates where it reads it, would
            // see it unset if it were taken out from above that line
            Replaced replaced = replaceAssignments(Files.readString(file, StandardCharsets.UTF_8), properties);
            Files.writeString(file, replaced.text(), StandardCharsets.UTF_8);
            absent = replaced.absent();
            block.append(replaced.text().endsWith("\n") || replaced.text().isEmpty() ? "" : "\n");
        }
        if (absent.isEmpty()) {
            return;
        }
        block.append('\n').append(BLOCK_HEADER).append('\n');
        absent.forEach((name, value) -> block.append(name).append('=').append(value).append('\n'));
        Files.writeString(file, block, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * @param text   the text, with the properties changed where they are assigned
     * @param absent the properties that the text did not assign, which are left to be written
     */
    record Replaced(String text, Map<String, String> absent) {
    }

    /**
     * @return the text with the line that first assigns each of the properties, {@code NAME=...}, which a properties file sourced by a shell has one to
     * a line, giving it the new value where it is, and the later lines that assign it taken out; the names that no line assigns are the absent ones
     */
    static Replaced replaceAssignments(String text, Map<String, String> properties) {
        Map<String, String> absent = new LinkedHashMap<>(properties);
        if (properties.isEmpty()) {
            return new Replaced(text, absent);
        }
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int equals = line.indexOf('=');
            String name = equals > 0 ? line.substring(0, equals) : null;
            boolean last = i == lines.length - 1;
            if (name != null && properties.containsKey(name)) {
                if (absent.remove(name) != null) {
                    out.append(name).append('=').append(properties.get(name)).append(last ? "" : "\n");
                }
                // else a later line for a property that is already given: left out
            } else {
                out.append(line).append(last ? "" : "\n");
            }
        }
        return new Replaced(out.toString(), absent);
    }

    /**
     * @return the afon home, {@link ConfigUtils#afonHome()}, as a path
     */
    public static Path defaultAfonHome() {
        return Path.of(ConfigUtils.afonHome());
    }
}
