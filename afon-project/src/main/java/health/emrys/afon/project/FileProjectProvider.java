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
import java.util.Map;
import java.util.Set;


/**
 * An {@link AfonProjectProvider} backed by a {@value #FILE}, which the deployer writes, in a dir of its own named by the project,
 * {@code <afon-home>/projects/<project>/project.yaml}, see {@link #forProject}.  The file is read, strictly, and validated when the
 * project is first asked for, and read once.  Its format, see {@link AfonProject}:
 * <pre>
 * project:   {id, cloud, env}      env is the env whose config it is made from, eg dev for config-dev: the active one if not given;
 *                                   where it deploys (region, account, node pools, names) comes from that config
 * identity:  {issuerUrl, acr, clients: {tre, git}, projectClaim, userClaim}     clients default to &lt;project&gt;-tre and &lt;project&gt;-git,
 *                                                                              the claims to project and polaris_principal
 * providers: [{id, polarisUri, warehouse, audience, bucket, cloud}]     cloud defaults to the project's
 * stacks:
 *   create:  {auditBucket, gitBucket}     gitBucket is optional: the config the project is made from says if it is not given
 *   git:     {domain}                     optional: without it Git is deployed with the TRE, and has no client
 *   tre:     {stack, domains: {&lt;PROPERTY&gt;: &lt;domain&gt;}, lifetime: {teardown, timeZone}}     stack defaults to &lt;cloud&gt;-full
 * </pre>
 * Reading is strict: an unknown key is a problem, as a misspelt one would otherwise be ignored silently, and every problem
 * found is reported together, see {@link ProjectException}.
 */
public class FileProjectProvider implements AfonProjectProvider {

    /**
     * The name of a project's file, in its dir.
     */
    public static final String FILE = "project.yaml";

    /**
     * The dir of the Afon home holding a dir for each project.
     */
    public static final String PROJECTS_DIR = "projects";

    /**
     * The dir of a project's dir holding the public keys of its clients, {@code tre.jwk} and {@code git.jwk}, which the TRE
     * operator gives: only public keys, as the private keys stay with the operator.
     */
    public static final String KEYS_DIR = "keys";

    private final Path file;
    private final String expectedId;
    private AfonProject project;

    /**
     * Provides the project of the file, whatever its id.
     */
    public FileProjectProvider(Path file) {
        this(file, null);
    }

    private FileProjectProvider(Path file, String expectedId) {
        this.file = file;
        this.expectedId = expectedId;
    }

    /**
     * Provides the project that the deployer defines in its own dir, {@code <afon-home>/projects/<project>/project.yaml}, so that a
     * project is found by its id alone.  The id of the file must be that of its dir.
     *
     * @throws IllegalArgumentException if {@code project} is not a valid project id, which also keeps it from naming a path
     */
    public static FileProjectProvider forProject(Path afonHome, String project) {
        return new FileProjectProvider(projectFile(afonHome, project), project);
    }

    /**
     * @return the dir of the project, {@code <afon-home>/projects/<project>}
     * @throws IllegalArgumentException if {@code project} is not a valid project id
     */
    public static Path projectDir(Path afonHome, String project) {
        return afonHome.resolve(PROJECTS_DIR).resolve(new AfonProject.ProjectId(project).value());
    }

    /**
     * @return the dir of the public keys of the project's clients, {@code <afon-home>/projects/<project>/keys}
     * @throws IllegalArgumentException if {@code project} is not a valid project id
     */
    public static Path keysDir(Path afonHome, String project) {
        return projectDir(afonHome, project).resolve(KEYS_DIR);
    }

    /**
     * @return the file of the project, {@code <afon-home>/projects/<project>/project.yaml}
     * @throws IllegalArgumentException if {@code project} is not a valid project id
     */
    public static Path projectFile(Path afonHome, String project) {
        return projectDir(afonHome, project).resolve(FILE);
    }

    /**
     * @return the project defined by the text, as it would be by a file, eg for tests
     * @throws ProjectException if it is not a valid project
     */
    public static AfonProject parse(String yaml) {
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
    public synchronized AfonProject getProject() {
        if (project == null) {
            AfonProject loaded = Parser.load(file);
            if (expectedId != null && !expectedId.equals(loaded.id().value())) {
                throw new ProjectException(file.toString(), List.of(String.format(
                    "project.id '%s' is not '%s', the name of the dir that holds it", loaded.id(), expectedId)));
            }
            project = loaded;
        }
        return project;
    }

    /**
     * Parses and validates the text of one project file.  It holds the problems found so far, which every check adds to, so
     * that all are reported together: one is made for each parse.
     */
    private static final class Parser {

        private static final String DEFAULT_TIME_ZONE = "Europe/London";

        private final String source;
        private final List<String> problems = new ArrayList<>();

        private Parser(String source) {
            this.source = source;
        }

        /**
         * @throws ProjectException     if the file is not a valid project
         * @throws IllegalStateException if it cannot be read
         */
        static AfonProject load(Path file) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return new Parser(file.toString()).parse(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
            }
        }

        /**
         * @throws ProjectException if the text is not a valid project
         */
        static AfonProject parse(String yaml) {
            return new Parser(FILE).parse(new StringReader(yaml));
        }

        private AfonProject parse(Reader reader) {
            Object root;
            try {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                root = new Yaml(new SafeConstructor(options)).load(reader);
            } catch (RuntimeException e) {
                throw new ProjectException(source, List.of("not valid YAML: " + e.getMessage()));
            }
            Map<String, Object> top = map(root, "", Set.of("project", "identity", "providers", "stacks"));
            Map<String, Object> project = map(top.get("project"), "project", Set.of("id", "cloud", "env"));
            AfonProject.ProjectId id = id(project);
            AfonCloud cloud = cloud(string(project, "project.cloud"), "project.cloud");
            String env = env(project);

            Map<String, Object> stacks = map(top.get("stacks"), "stacks", Set.of("create", "git", "tre"));
            boolean ownGit = stacks.get("git") != null;
            AfonProject.Identity identity = identity(map(top.get("identity"), "identity",
                                                          Set.of("issuerUrl", "acr", "clients", "projectClaim", "userClaim")), id, ownGit);
            List<AfonProject.Provider> providers = providers(top.get("providers"), cloud);

            Map<String, Object> create = map(stacks.get("create"), "stacks.create", Set.of("auditBucket", "gitBucket"));
            Map<String, Object> git = map(stacks.get("git"), "stacks.git", Set.of("domain"));
            AfonProject.Create createStack = new AfonProject.Create(string(create, "stacks.create.auditBucket"),
                                                                    optional(create, "stacks.create.gitBucket", null));
            AfonProject.Git gitStack = ownGit ? new AfonProject.Git(string(git, "stacks.git.domain")) : null;
            AfonProject.Tre treStack = tre(map(stacks.get("tre"), "stacks.tre", Set.of("stack", "domains", "lifetime")), cloud);

            if (!problems.isEmpty()) {
                throw new ProjectException(source, problems);
            }
            return new AfonProject(id, cloud, env, identity, providers, createStack, gitStack, treStack);
        }

        /**
         * @return the env whose config the project is made from, or {@code null} if it is not given or, noting a problem, not the name of an env:
         * it names a dir, {@code config-<env>}, so it is no path
         */
        private String env(Map<String, Object> project) {
            String env = optional(project, "project.env", null);
            if (env != null && !env.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                problems.add(String.format("project.env '%s' is not the name of an env, eg dev for config-dev", env));
                return null;
            }
            return env;
        }

        /**
         * @return the project's id, or {@code null}, noting a problem, if it is missing or not valid
         */
        private AfonProject.ProjectId id(Map<String, Object> project) {
            String value = string(project, "project.id");
            if (value == null) {
                return null;
            }
            try {
                return new AfonProject.ProjectId(value);
            } catch (IllegalArgumentException e) {
                problems.add("project.id: " + e.getMessage());
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
         * @param ownGit whether the project has a Git of its own, which has a client: without, Git is deployed with the TRE
         */
        private AfonProject.Identity identity(Map<String, Object> identity, AfonProject.ProjectId id, boolean ownGit) {
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
            return new AfonProject.Identity(issuer, string(identity, "identity.acr"), tre, git,
                                            optional(identity, "identity.projectClaim", AfonProject.Identity.DEFAULT_PROJECT_CLAIM),
                                            optional(identity, "identity.userClaim", AfonProject.Identity.DEFAULT_USER_CLAIM));
        }

        private List<AfonProject.Provider> providers(Object node, AfonCloud projectCloud) {
            List<AfonProject.Provider> providers = new ArrayList<>();
            if (node == null) {
                problems.add("providers is missing: a project reads the data of at least one Provider");
                return providers;
            }
            if (!(node instanceof List<?> list) || list.isEmpty()) {
                problems.add("providers must be a non-empty list");
                return providers;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < list.size(); i++) {
                String path = "providers[" + i + "]";
                Map<String, Object> provider = map(list.get(i), path,
                                                   Set.of("id", "polarisUri", "warehouse", "audience", "bucket", "cloud"));
                String id = string(provider, path + ".id");
                if (id != null && !ids.add(id)) {
                    problems.add(String.format("%s.id '%s' is used by another provider", path, id));
                }
                String cloudName = optional(provider, path + ".cloud", projectCloud == null ? null : projectCloud.toString());
                providers.add(new AfonProject.Provider(id, string(provider, path + ".polarisUri"),
                                                       string(provider, path + ".warehouse"), string(provider, path + ".audience"),
                                                       string(provider, path + ".bucket"), cloud(cloudName, path + ".cloud")));
            }
            return providers;
        }

        private AfonProject.Tre tre(Map<String, Object> tre, AfonCloud cloud) {
            String stackName = optional(tre, "stacks.tre.stack", cloud == null ? null : cloud + "-full");
            StackId stack = null;
            if (stackName != null) {
                try {
                    stack = StackId.parse(stackName);
                    if (cloud != null && stack.cloud() != cloud) {
                        problems.add(String.format("stacks.tre.stack '%s' is not on the project's cloud, %s", stackName, cloud));
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
            return new AfonProject.Tre(stack, domainsByProperty, teardown,
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
