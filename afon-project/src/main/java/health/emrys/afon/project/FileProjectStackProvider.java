package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.StackId;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


/**
 * An {@link ProjectStackProvider} backed by a {@value #FILE}, which the deployer writes, in a dir of its own named by the project stack,
 * {@code <afon-home>/projects/<project-stack>/project.yaml}, see {@link #forProjectStack}.  The file is read, strictly, and validated when the
 * project stack is first asked for, and read once.  Its format, see {@link ProjectStack}:
 * <pre>
 * projectStack:   {id, cloud, role, env, egressIp}   role is identity or tre (the default), see {@link ProjectStackRole}, and decides the rest;
 *                                   env is the env whose config it is made from, eg dev for config-dev: the active one if not given;
 *                                   egressIp is the address its cluster leaves from, which the Data Providers allowlist: the env's if not given;
 *                                   where it deploys (region, account, node pools, names) comes from that config
 * dns:       {delegation}                          optional: manual, the default, or route53, to have up delegate the zones of its domains from the Route 53
 *                                   hosted zone of the parent domain, in the current AWS account, rather than by hand
 * cluster:   {nodePool: {vmSize, nodeCount}}      optional: the nodes of its cluster, with the keys of the cloud's node pool property
 *                                   (Azure vmSize and nodeCount, GCP machineType and nodeCount, none on AWS): the env's if not given
 * identity:  {issuerUrl, acr, clients: {tre, git}, projectClaim, userClaim, projectStack}     projectStack is the id of the identity project stack;
 *                                   acr is the authentication context class reference that
 *                                   proves MFA, eg mfa; clients default to &lt;project-stack&gt;-tre and &lt;project-stack&gt;-git,
 *                                                                              the claims to project and polaris_principal
 * dataProviders: {named: [{projectStack, catalogs}], defined: [{id, catalogUri, catalogName, audience, bucket, cloud}]}     cloud defaults to the project stack's;
 *                                   projectStack is the id of a data project stack of the Afon home, whose catalogs the entry then takes the endpoint, the audience
 *                                   and the storage of from it, with the ids &lt;project-stack&gt;-&lt;catalog&gt;
 * members:   [email | {email, firstName, lastName}]     the project stack's members, at least one, which up makes its group have, and no others;
 * smokeMembers: [email | {email, firstName, lastName}]     members for the smoke tests to sign in as, kept apart from the members that people use, and in the group
 *                                   too; the first is the one that SMOKE_MEMBER names; optional, and none may also be a member
 * stacks:
 *   create:  {auditBucket, gitBucket}     gitBucket is optional: the config the project stack is made from says if it is not given
 *   git:     {domain}                     optional: without it Git is deployed with the TRE, and has no client
 *   tre:     {stack, domains: {&lt;PROPERTY&gt;: &lt;domain&gt;}, lifetime: {teardown, timeZone}}     stack defaults to &lt;cloud&gt;-full
 * </pre>
 * A project stack with the role {@code identity} deploys the central identity, and has only {@code projectStack} and
 * <pre>
 * stacks:
 *   identity: {stack, domain}                  stack defaults to &lt;cloud&gt;-keycloak; domain is {@code <CLOUD>_OIDC_DOMAIN_NAME}, the env's with the project stack id as a label after the first if not given
 * </pre>
 * no {@code identity}, {@code dataProviders}, {@code create}, {@code git} or {@code tre}.  A project stack with the role {@code data} deploys a Data Provider's
 * Polaris, and has only {@code projectStack}, {@code dns}, {@code cluster} and
 * <pre>
 * identity:  {issuerUrl, acr, audience, projectStack}  the central identity that its Polaris trusts: audience defaults to provider-&lt;id&gt;
 * catalogs:  [id]                                 its catalogs, at least one, eg [cvd, dbt]: lower-case letters, digits and underscores, the first a letter
 * stacks:
 *   data:    {stack, domain}                     stack defaults to &lt;cloud&gt;-polaris; domain is {@code <CLOUD>_POLARIS_DOMAIN_NAME}, the env's with the project stack id as a label after the first if not given
 * </pre>
 * <p>
 * Reading is strict: an unknown key is a problem, as a misspelt one would otherwise be ignored silently, and every problem
 * found is reported together, see {@link ProjectStackException}.
 */
public class FileProjectStackProvider implements ProjectStackProvider {

    /**
     * The name of a project stack's file, in its dir.
     */
    public static final String FILE = "project.yaml";

    /**
     * The dir of the Afon home holding a dir for each project stack.
     */
    public static final String PROJECTS_DIR = "projects";

    /**
     * The dir of a project stack's dir holding the secrets of its clients, {@code tre.secret} and {@code git.secret}, which
     * {@code project-secret} makes: the project stack's config is given the TRE's, and the central identity the same.
     */
    public static final String SECRETS_DIR = "secrets";

    private final Path file;
    private final String expectedId;
    /** The Afon home that the project stack is of, which the Data Providers it names are looked up in, or {@code null} if it is not known. */
    private final Path afonHome;
    private ProjectStack projectStack;

    /**
     * Provides the project stack of the file, whatever its id.
     */
    public FileProjectStackProvider(Path file) {
        this(file, null, null);
    }

    private FileProjectStackProvider(Path file, String expectedId, Path afonHome) {
        this.file = file;
        this.expectedId = expectedId;
        this.afonHome = afonHome;
    }

    /**
     * Provides the project stack that the deployer defines in its own dir, {@code <afon-home>/projects/<project-stack>/project.yaml}, so that a
     * project stack is found by its id alone.  The id of the file must be that of its dir.
     *
     * @throws IllegalArgumentException if {@code projectStack} is not a valid project stack id, which also keeps it from naming a path
     */
    public static FileProjectStackProvider forProjectStack(Path afonHome, String projectStack) {
        return new FileProjectStackProvider(projectFile(afonHome, projectStack), projectStack, afonHome);
    }

    /**
     * @return the dir of the project stack, {@code <afon-home>/projects/<project-stack>}
     * @throws IllegalArgumentException if {@code projectStack} is not a valid project stack id
     */
    public static Path projectDir(Path afonHome, String projectStack) {
        return afonHome.resolve(PROJECTS_DIR).resolve(new ProjectStack.ProjectStackId(projectStack).value());
    }

    /**
     * @return the dir of the secrets of the project stack's clients, {@code <afon-home>/projects/<project-stack>/secrets}
     * @throws IllegalArgumentException if {@code projectStack} is not a valid project stack id
     */
    public static Path secretsDir(Path afonHome, String projectStack) {
        return projectDir(afonHome, projectStack).resolve(SECRETS_DIR);
    }

    /**
     * @return the file of the project stack, {@code <afon-home>/projects/<project-stack>/project.yaml}
     * @throws IllegalArgumentException if {@code projectStack} is not a valid project stack id
     */
    public static Path projectFile(Path afonHome, String projectStack) {
        return projectDir(afonHome, projectStack).resolve(FILE);
    }

    /**
     * @return the project stack defined by the text, as it would be by a file, eg for tests
     * @throws ProjectStackException if it is not a valid project stack
     */
    public static ProjectStack parse(String yaml) {
        return Parser.parse(yaml);
    }

    /**
     * @return the file this provider reads
     */
    public Path file() {
        return file;
    }

    /**
     * @return whether the file exists
     */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    @Override
    public synchronized ProjectStack getProjectStack() {
        if (projectStack == null) {
            ProjectStack loaded = Parser.load(file, afonHome);
            if (expectedId != null && !expectedId.equals(loaded.id().value())) {
                throw new ProjectStackException(file.toString(), List.of(String.format(
                    "projectStack.id '%s' is not '%s', the name of the dir that holds it", loaded.id(), expectedId)));
            }
            projectStack = loaded;
        }
        return projectStack;
    }

    /**
     * Parses and validates the text of one project stack file.  It holds the problems found so far, which every check adds to, so
     * that all are reported together: one is made for each parse.
     */
    private static final class Parser {

        private static final String DEFAULT_TIME_ZONE = "Europe/London";

        private final String source;
        private final Path afonHome;
        private final List<String> problems = new ArrayList<>();

        private Parser(String source, Path afonHome) {
            this.source = source;
            this.afonHome = afonHome;
        }

        /**
         * @throws ProjectStackException     if the file is not a valid project stack
         * @throws IllegalStateException if it cannot be read
         */
        static ProjectStack load(Path file, Path afonHome) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return new Parser(file.toString(), afonHome).parse(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
            }
        }

        /**
         * @throws ProjectStackException if the text is not a valid project stack
         */
        static ProjectStack parse(String yaml) {
            return new Parser(FILE, null).parse(new StringReader(yaml));
        }

        private ProjectStack parse(Reader reader) {
            Object root;
            try {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                root = new Yaml(new SafeConstructor(options)).load(reader);
            } catch (RuntimeException e) {
                throw new ProjectStackException(source, List.of("not valid YAML: " + e.getMessage()));
            }
            ProjectStackRole role = role(root);
            boolean identityRole = role == ProjectStackRole.IDENTITY;
            boolean dataRole = role == ProjectStackRole.DATA;
            Map<String, Object> top = map(root, "", identityRole ? Set.of("projectStack", "cluster", "dns", "stacks")
                                                   : dataRole ? Set.of("projectStack", "cluster", "dns", "identity", "catalogs", "stacks")
                                                   : Set.of("projectStack", "cluster", "dns", "identity", "dataProviders", "members", "smokeMembers", "stacks"));
            Map<String, Object> projectStack = map(top.get("projectStack"), "projectStack", Set.of("id", "cloud", "role", "env", "egressIp"));
            ProjectStack.ProjectStackId id = id(projectStack);
            AfonCloud cloud = cloud(string(projectStack, "projectStack.cloud"), "projectStack.cloud");
            String env = env(projectStack);
            String egressIp = egressIp(projectStack);
            Map<String, String> nodePool = nodePool(map(top.get("cluster"), "cluster", Set.of("nodePool")), cloud);
            DnsDelegation dnsDelegation = dnsDelegation(map(top.get("dns"), "dns", Set.of("delegation")));

            Map<String, Object> stacks = map(top.get("stacks"), "stacks", identityRole ? Set.of("identity") : dataRole ? Set.of("data") : Set.of("create", "git", "tre"));
            if (dataRole) {
                List<String> catalogs = catalogs(top.get("catalogs"));
                DataProjectStack.Identity dataIdentity = dataIdentity(map(top.get("identity"), "identity", Set.of("issuerUrl", "acr", "audience", "projectStack")), id);
                if (!problems.isEmpty()) {
                    throw new ProjectStackException(source, problems);
                }
                DataProjectStack dataProjectStack = dataProjectStack(id, cloud, env, egressIp, nodePool, dnsDelegation, dataIdentity, catalogs,
                                                      map(stacks.get("data"), "stacks.data", Set.of("stack", "domain")));
                if (!problems.isEmpty()) {
                    throw new ProjectStackException(source, problems);
                }
                checkClusterNames(dataProjectStack);
                return dataProjectStack;
            }
            if (identityRole) {
                if (!problems.isEmpty()) {
                    throw new ProjectStackException(source, problems);
                }
                IdentityProjectStack identityProjectStack = identityProjectStack(id, cloud, env, egressIp, nodePool, dnsDelegation,
                                                                  map(stacks.get("identity"), "stacks.identity", Set.of("stack", "domain")));
                if (!problems.isEmpty()) {
                    throw new ProjectStackException(source, problems);
                }
                checkClusterNames(identityProjectStack);
                return identityProjectStack;
            }
            boolean ownGit = stacks.get("git") != null;
            TreProjectStack.Identity identity = identity(map(top.get("identity"), "identity",
                                                          Set.of("issuerUrl", "acr", "clients", "projectClaim", "userClaim", "projectStack")), id, ownGit);
            List<TreProjectStack.DataProvider> dataProviders = dataProviders(top.get("dataProviders"), cloud, identity.issuerUrl());
            List<TreProjectStack.Member> members = members(top.get("members"), "members", true, List.of());
            List<TreProjectStack.Member> smokeMembers = members(top.get("smokeMembers"), "smokeMembers", false, members);

            Map<String, Object> create = map(stacks.get("create"), "stacks.create", Set.of("auditBucket", "gitBucket"));
            Map<String, Object> git = map(stacks.get("git"), "stacks.git", Set.of("domain"));
            TreProjectStack.Create createStack = new TreProjectStack.Create(string(create, "stacks.create.auditBucket"),
                                                                    optional(create, "stacks.create.gitBucket", null));
            TreProjectStack.Git gitStack = ownGit ? new TreProjectStack.Git(string(git, "stacks.git.domain")) : null;
            TreProjectStack.Tre treStack = tre(map(stacks.get("tre"), "stacks.tre", Set.of("stack", "domains", "lifetime")), cloud);

            if (!problems.isEmpty()) {
                throw new ProjectStackException(source, problems);
            }
            TreProjectStack treProjectStack = new TreProjectStack(id, cloud, env, egressIp, nodePool, dnsDelegation, identity, dataProviders, members, smokeMembers, createStack, gitStack, treStack);
            checkClusterNames(treProjectStack);
            return treProjectStack;
        }

        /** The project stack's id is in the name of its clusters, which each cloud limits. */
        private void checkClusterNames(ProjectStack projectStack) {
            List<String> tooLong = ProjectStackNames.clusterNameProblems(projectStack);
            if (!tooLong.isEmpty()) {
                throw new ProjectStackException(source, tooLong);
            }
        }

        /**
         * @return the role the file gives the project stack, {@link ProjectStackRole#TRE} if it gives none, or, noting a problem, if it gives one that
         * is not a role, so that the rest is read as for a TRE project stack, which is what a project stack mostly is
         */
        private ProjectStackRole role(Object root) {
            Object projectStack = root instanceof Map<?, ?> top ? top.get("projectStack") : null;
            Object value = projectStack instanceof Map<?, ?> map ? map.get("role") : null;
            if (value == null || String.valueOf(value).isBlank()) {
                return ProjectStackRole.TRE;
            }
            return ProjectStackRole.of(String.valueOf(value).trim()).orElseGet(() -> {
                problems.add(String.format("projectStack.role '%s' is not a role of a project stack: one of %s", value,
                                           Arrays.stream(ProjectStackRole.values()).map(ProjectStackRole::value).toList()));
                return ProjectStackRole.TRE;
            });
        }

        /**
         * @return the identity project stack, whose stack is {@code <cloud>-keycloak} if it names none, which the stacks file may define otherwise: it is
         * looked up when the project stack runs, as for a TRE's
         */
        private IdentityProjectStack identityProjectStack(ProjectStack.ProjectStackId id, AfonCloud cloud, String env, String egressIp, Map<String, String> nodePool,
                                                DnsDelegation dnsDelegation,
                                                Map<String, Object> identity) {
            StackId stack = stackOf(identity, "stacks.identity", cloud, "keycloak");
            return new IdentityProjectStack(id, cloud, env, egressIp, nodePool, dnsDelegation, stack, optional(identity, "stacks.identity.domain", null));
        }

        /**
         * @return the data project stack, whose stack is {@code <cloud>-polaris} if it names none, which the stacks file may define otherwise
         */
        private DataProjectStack dataProjectStack(ProjectStack.ProjectStackId id, AfonCloud cloud, String env, String egressIp, Map<String, String> nodePool,
                                        DnsDelegation dnsDelegation, DataProjectStack.Identity identity, List<String> catalogs, Map<String, Object> data) {
            StackId stack = stackOf(data, "stacks.data", cloud, "polaris");
            return new DataProjectStack(id, cloud, env, egressIp, nodePool, dnsDelegation, identity, catalogs, stack, optional(data, "stacks.data.domain", null));
        }

        /**
         * @return how the Data Provider uses the central identity: its issuer, which has to be https as every consumer pins it, the {@code acr} that proves a
         * second factor, and its audience, {@code provider-<id>} if the file gives none
         */
        private DataProjectStack.Identity dataIdentity(Map<String, Object> identity, ProjectStack.ProjectStackId id) {
            String issuer = string(identity, "identity.issuerUrl");
            if (issuer != null && !issuer.startsWith("https://")) {
                problems.add("identity.issuerUrl must be an https URL, as every consumer pins it");
            }
            return new DataProjectStack.Identity(issuer, string(identity, "identity.acr"),
                                            optional(identity, "identity.audience", id == null ? null : "provider-" + id), identityProjectStackId(identity));
        }

        /**
         * @return the stack that the section names, or {@code <cloud>-<default type>} if it names none; {@code null} if it is not a stack, noting a
         * problem, or if there is no cloud to say it by
         */
        private StackId stackOf(Map<String, Object> section, String path, AfonCloud cloud, String defaultType) {
            String name = optional(section, path + ".stack", cloud == null ? null : cloud.toString().toLowerCase(Locale.ROOT) + "-" + defaultType);
            if (name == null) {
                return null;
            }
            try {
                StackId stack = StackId.parse(name);
                if (cloud != null && stack.cloud() != cloud) {
                    problems.add(String.format("%s.stack '%s' is not on the project stack's cloud, %s", path, name, cloud));
                }
                return stack;
            } catch (IllegalArgumentException e) {
                problems.add(path + ".stack: " + e.getMessage());
                return null;
            }
        }

        private static final java.util.regex.Pattern CATALOG_ID = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,29}");

        /**
         * @return the ids of the catalogs, at least one: each is a lower-case letter followed by letters, digits and underscores, which names the
         * catalog's properties and the container or bucket of its storage; none, noting a problem, if the list is not a list of such ids
         */
        private List<String> catalogs(Object node) {
            List<String> catalogs = new ArrayList<>();
            if (node == null) {
                problems.add("catalogs is missing: a Data Provider has at least one catalog");
                return catalogs;
            }
            if (!(node instanceof List<?> list) || list.isEmpty()) {
                problems.add("catalogs must be a non-empty list of catalog ids");
                return catalogs;
            }
            for (int i = 0; i < list.size(); i++) {
                String path = "catalogs[" + i + "]";
                String id = list.get(i) == null ? "" : String.valueOf(list.get(i)).trim();
                if (!CATALOG_ID.matcher(id).matches()) {
                    problems.add(String.format("%s '%s' is not a catalog id: a lower-case letter, then at most 29 letters, digits or underscores", path, id));
                } else if (catalogs.contains(id)) {
                    problems.add(String.format("%s '%s' is the id of another catalog", path, id));
                } else {
                    catalogs.add(id);
                }
            }
            return catalogs;
        }

        /**
         * @return the id of the identity project stack that the TRE project stack names, or {@code null} if it names none or, noting a problem, not an id
         */
        private String identityProjectStackId(Map<String, Object> identity) {
            String id = optional(identity, "identity.projectStack", null);
            if (id == null) {
                return null;
            }
            try {
                return new ProjectStack.ProjectStackId(id).value();
            } catch (IllegalArgumentException e) {
                problems.add("identity.projectStack: " + e.getMessage());
                return null;
            }
        }

        private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");

        /**
         * @return the address the project stack's cluster leaves from, or {@code null} if it is not given or, noting a problem, not an IPv4 address,
         * which is what the static IPs of the clouds that pin one are
         */
        /**
         * @return the node pool of the cluster, whose keys are those of the cloud's node pool property, each with a value that the property
         * takes: a number of nodes is a whole number of at least 1; empty if there is none
         */
        private Map<String, String> nodePool(Map<String, Object> cluster, AfonCloud cloud) {
            Map<String, Object> pool = map(cluster.get("nodePool"), "cluster.nodePool", null);
            Map<String, String> result = new LinkedHashMap<>();
            if (pool.isEmpty() || cloud == null) {
                return result;
            }
            Set<String> keys = ProjectStack.NODE_POOL_KEYS.get(cloud);
            if (keys.isEmpty()) {
                problems.add(String.format("cluster.nodePool is not available on %s, which has no node pool property: the config the project stack is made from has the nodes",
                                           cloud.toString().toLowerCase(Locale.ROOT)));
                return result;
            }
            pool.forEach((key, value) -> {
                String text = value == null ? "" : String.valueOf(value).trim();
                if (!keys.contains(key)) {
                    problems.add(String.format("cluster.nodePool.%s is not a known key on %s, expected one of %s", key,
                                               cloud.toString().toLowerCase(Locale.ROOT), new java.util.TreeSet<>(keys)));
                } else if (text.isEmpty() || text.matches(".*[,;:\\s].*")) {
                    problems.add(String.format("cluster.nodePool.%s '%s' is not a value, it has a separator or a space", key, text));
                } else if (key.equals("nodeCount") && !text.matches("[1-9][0-9]*")) {
                    problems.add(String.format("cluster.nodePool.nodeCount '%s' is not a number of nodes, a whole number of at least 1", text));
                } else {
                    result.put(key, text);
                }
            });
            return result;
        }

        /**
         * @return how the zones of the project stack's domains are delegated: manual unless the file says route53; {@code none}, or no value, is
         * manual: no automatic delegation
         */
        private DnsDelegation dnsDelegation(Map<String, Object> dns) {
            String value = optional(dns, "dns.delegation", DnsDelegation.MANUAL.value());
            return DnsDelegation.of(value.equals("none") ? DnsDelegation.MANUAL.value() : value).orElseGet(() -> {
                problems.add(String.format("dns.delegation '%s' is not a way to delegate the DNS zones: one of %s", value,
                                           Arrays.stream(DnsDelegation.values()).map(DnsDelegation::value).toList()));
                return DnsDelegation.MANUAL;
            });
        }

        private String egressIp(Map<String, Object> projectStack) {
            String egressIp = optional(projectStack, "projectStack.egressIp", null);
            if (egressIp != null && !IPV4.matcher(egressIp).matches()) {
                problems.add(String.format("projectStack.egressIp '%s' is not an IPv4 address, eg the one that azure-egress-ip create prints", egressIp));
                return null;
            }
            return egressIp;
        }

        /**
         * @return the env whose config the project stack is made from, or {@code null} if it is not given or, noting a problem, not the name of an env:
         * it names a dir, {@code config-<env>}, so it is no path
         */
        private String env(Map<String, Object> projectStack) {
            String env = optional(projectStack, "projectStack.env", null);
            if (env != null && !env.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                problems.add(String.format("projectStack.env '%s' is not the name of an env, eg dev for config-dev", env));
                return null;
            }
            return env;
        }

        /**
         * @return the project stack's id, or {@code null}, noting a problem, if it is missing or not valid
         */
        private ProjectStack.ProjectStackId id(Map<String, Object> projectStack) {
            String value = string(projectStack, "projectStack.id");
            if (value == null) {
                return null;
            }
            try {
                return new ProjectStack.ProjectStackId(value);
            } catch (IllegalArgumentException e) {
                problems.add("projectStack.id: " + e.getMessage());
                return null;
            }
        }

        private AfonCloud cloud(String value, String path) {
            if (value == null) {
                return null;
            }
            try {
                return AfonCloud.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                problems.add(String.format("%s '%s' is not a known cloud: aws, azure or gcp", path, value));
                return null;
            }
        }

        /**
         * @param ownGit whether the project stack has a Git of its own, which has a client: without, Git is deployed with the TRE
         */
        private TreProjectStack.Identity identity(Map<String, Object> identity, ProjectStack.ProjectStackId id, boolean ownGit) {
            Map<String, Object> clients = map(identity.get("clients"), "identity.clients", Set.of("tre", "git"));
            String tre = optional(clients, "identity.clients.tre", id == null ? null : id + "-tre");
            String git = ownGit ? optional(clients, "identity.clients.git", id == null ? null : id + "-git") : null;
            if (!ownGit && clients.get("git") != null) {
                problems.add("identity.clients.git is set but there is no stacks.git: without its own Git, Git is deployed with the TRE, and has no client");
            }
            String issuer = string(identity, "identity.issuerUrl");
            if (issuer != null && !issuer.startsWith("https://")) {
                problems.add("identity.issuerUrl must be an https URL, as every consumer pins it");
            }
            return new TreProjectStack.Identity(issuer, string(identity, "identity.acr"), tre, git,
                                            optional(identity, "identity.projectClaim", TreProjectStack.Identity.DEFAULT_PROJECT_CLAIM),
                                            optional(identity, "identity.userClaim", TreProjectStack.Identity.DEFAULT_USER_CLAIM),
                                            identityProjectStackId(identity));
        }

        private List<TreProjectStack.DataProvider> dataProviders(Object node, AfonCloud projectCloud, String issuerUrl) {
            List<TreProjectStack.DataProvider> dataProviders = new ArrayList<>();
            if (node == null) {
                problems.add("dataProviders is missing: a project stack reads the data of at least one Data Provider");
                return dataProviders;
            }
            if (!(node instanceof Map<?, ?>)) {
                problems.add("dataProviders must be a mapping of named and defined");
                return dataProviders;
            }
            Map<String, Object> top = map(node, "dataProviders", Set.of("named", "defined"));
            Set<String> ids = new LinkedHashSet<>();
            for (Object entry : list(top.get("named"), "dataProviders.named")) {
                String path = "dataProviders.named[" + indexOf(top.get("named"), entry) + "]";
                for (TreProjectStack.DataProvider named : namedProviders(map(entry, path, Set.of("projectStack", "catalogs")), path, issuerUrl)) {
                    if (!ids.add(named.id())) {
                        problems.add(String.format("%s: the id '%s' is used by another Data Provider", path, named.id()));
                    } else {
                        dataProviders.add(named);
                    }
                }
            }
            for (Object entry : list(top.get("defined"), "dataProviders.defined")) {
                String path = "dataProviders.defined[" + indexOf(top.get("defined"), entry) + "]";
                Map<String, Object> provider = map(entry, path, Set.of("id", "catalogUri", "catalogName", "audience", "bucket", "cloud"));
                String id = string(provider, path + ".id");
                if (id != null && !ids.add(id)) {
                    problems.add(String.format("%s.id '%s' is used by another Data Provider", path, id));
                }
                String cloudName = optional(provider, path + ".cloud", projectCloud == null ? null : projectCloud.toString());
                dataProviders.add(new TreProjectStack.DataProvider(id, string(provider, path + ".catalogUri"),
                                                       string(provider, path + ".catalogName"), string(provider, path + ".audience"),
                                                       string(provider, path + ".bucket"), cloud(cloudName, path + ".cloud")));
            }
            if (dataProviders.isEmpty() && problems.isEmpty()) {
                problems.add("dataProviders names no catalog: a project stack reads the data of at least one Data Provider, in named or defined");
            }
            return dataProviders;
        }

        /** @return the entries of the list at the path, none if it is absent, noting a problem if it is not a list */
        private List<Object> list(Object node, String path) {
            if (node == null) {
                return List.of();
            }
            if (!(node instanceof List<?> list)) {
                problems.add(path + " must be a list");
                return List.of();
            }
            return new ArrayList<>(list);
        }

        private static int indexOf(Object list, Object entry) {
            return ((List<?>) list).indexOf(entry);
        }

        /**
         * @return the catalogs of the Data Provider's project stack that the entry names, {@code catalogs}, whose ids are the project stack's and the catalog's,
         * {@code lon-cvd}; each as that project stack's file and config say it.  Those that cannot be read are left out, noting a problem: this has no Afon home
         * to look the project stack up in, the project stack is not a Data Provider, or it has not the catalog
         */
        private List<TreProjectStack.DataProvider> namedProviders(Map<String, Object> entry, String path, String issuerUrl) {
            String projectStack = string(entry, path + ".projectStack");
            if (projectStack == null) {
                return List.of();
            }
            try {
                new ProjectStack.ProjectStackId(projectStack);
            } catch (IllegalArgumentException e) {
                problems.add(path + ".projectStack: " + e.getMessage());
                return List.of();
            }
            if (!(entry.get("catalogs") instanceof List<?> catalogs) || catalogs.isEmpty()) {
                problems.add(path + ".catalogs must be a non-empty list of the catalog ids of " + projectStack);
                return List.of();
            }
            if (afonHome == null) {
                problems.add(path + ".projectStack names " + projectStack + ", which is looked up in the project stacks of the Afon home, so by manage-project-stack, not here");
                return List.of();
            }
            List<TreProjectStack.DataProvider> dataProviders = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int j = 0; j < catalogs.size(); j++) {
                String catalog = catalogs.get(j) == null ? "" : String.valueOf(catalogs.get(j)).trim();
                if (catalog.isEmpty()) {
                    problems.add(path + ".catalogs[" + j + "] is blank");
                } else if (!seen.add(catalog)) {
                    problems.add(String.format("%s.catalogs[%d] '%s' is listed twice", path, j, catalog));
                } else {
                    TreProjectStack.DataProvider provider = DataProviderResolution.resolve(afonHome, projectStack, catalog, projectStack + "-" + catalog, issuerUrl, path, problems);
                    if (provider != null) {
                        dataProviders.add(provider);
                    }
                }
            }
            return dataProviders;
        }

        /**
         * @return the members under {@code key}, each an email or a mapping with an {@code email} and optionally a {@code firstName} and a {@code lastName}; the
         * members are required, at least one, the smoke members are not, and none of them is also in {@code others}, the members that people use
         */
        private List<TreProjectStack.Member> members(Object node, String key, boolean required, List<TreProjectStack.Member> others) {
            if (node == null || node instanceof List<?> none && none.isEmpty()) {
                if (required) {
                    problems.add(key + " is required: a project stack has at least one member, by email");
                }
                return List.of();
            }
            if (!(node instanceof List<?> list)) {
                problems.add(key + " must be a list of " + (required ? "at least one member" : "members"));
                return List.of();
            }
            List<TreProjectStack.Member> members = new ArrayList<>();
            Set<String> emails = new LinkedHashSet<>();
            for (int i = 0; i < list.size(); i++) {
                String path = key + "[" + i + "]";
                Map<String, Object> entry = list.get(i) instanceof Map<?, ?>
                    ? map(list.get(i), path, Set.of("email", "firstName", "lastName"))
                    : new LinkedHashMap<>(Map.of("email", list.get(i) == null ? "" : list.get(i)));
                String email = string(entry, path + ".email");
                if (email == null) {
                    continue;
                }
                email = email.toLowerCase(java.util.Locale.ROOT);
                if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    problems.add(String.format("%s '%s' is not an email", path + ".email", email));
                } else if (!emails.add(email)) {
                    problems.add(String.format("%s '%s' is listed twice", path + ".email", email));
                } else if (others.stream().anyMatch(other -> other.email().equals(entry.get("email").toString().toLowerCase(java.util.Locale.ROOT)))) {
                    problems.add(String.format("%s '%s' is also a member: the smoke members are kept apart from the members that people use", path + ".email", email));
                } else {
                    members.add(new TreProjectStack.Member(email, optional(entry, path + ".firstName", null), optional(entry, path + ".lastName", null)));
                }
            }
            return members;
        }

        private TreProjectStack.Tre tre(Map<String, Object> tre, AfonCloud cloud) {
            String stackName = optional(tre, "stacks.tre.stack", cloud == null ? null : cloud + "-full");
            StackId stack = null;
            if (stackName != null) {
                try {
                    stack = StackId.parse(stackName);
                    if (cloud != null && stack.cloud() != cloud) {
                        problems.add(String.format("stacks.tre.stack '%s' is not on the project stack's cloud, %s", stackName, cloud));
                    }
                } catch (IllegalArgumentException e) {
                    problems.add("stacks.tre.stack: " + e.getMessage());
                }
            }
            Map<String, Object> domains = map(tre.get("domains"), "stacks.tre.domains", null);
            Map<String, String> domainsByProperty = new LinkedHashMap<>();
            domains.forEach((property, domain) -> {
                if (Arrays.stream(AfonProperty.values()).noneMatch(known -> known.getName().equals(property))) {
                    problems.add(String.format("stacks.tre.domains.%s is not a known property", property));
                }
                domainsByProperty.put(property, string(domains, "stacks.tre.domains." + property));
            });
            Map<String, Object> lifetime = map(tre.get("lifetime"), "stacks.tre.lifetime", Set.of("teardown", "timeZone"));
            String teardown = lifetime.containsKey("teardown") ? string(lifetime, "stacks.tre.lifetime.teardown") : null;
            if (teardown != null && !teardown.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                problems.add(String.format("stacks.tre.lifetime.teardown '%s' is not a time as HH:mm, eg 22:00", teardown));
            }
            return new TreProjectStack.Tre(stack, domainsByProperty, teardown,
                                       optional(lifetime, "stacks.tre.lifetime.timeZone", DEFAULT_TIME_ZONE));
        }

        /**
         * @return the node as a map, empty if it is missing, noting a problem if it is neither, or has a key not in {@code known}
         * (any key, if {@code known} is {@code null})
         */
        private Map<String, Object> map(Object node, String path, Set<String> known) {
            if (node == null) {
                return new LinkedHashMap<>();
            }
            if (!(node instanceof Map<?, ?> raw)) {
                problems.add((path.isEmpty() ? "the file" : path) + " must be a mapping");
                return new LinkedHashMap<>();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (known != null && !known.contains(name)) {
                    problems.add(String.format("%s is not a known key%s, expected one of %s", path.isEmpty() ? name : path + "." + name,
                                               "", new java.util.TreeSet<>(known)));
                }
                result.put(name, value);
            });
            return result;
        }

        /**
         * @return the text of the required key whose full path is given, or {@code null}, noting a problem, if it is missing or blank
         */
        private String string(Map<String, Object> map, String path) {
            Object value = map.get(path.substring(path.lastIndexOf('.') + 1));
            if (value == null || String.valueOf(value).isBlank()) {
                problems.add(path + " is required");
                return null;
            }
            return String.valueOf(value).trim();
        }

        private String optional(Map<String, Object> map, String path, String defaultValue) {
            Object value = map.get(path.substring(path.lastIndexOf('.') + 1));
            return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value).trim();
        }
    }
}
