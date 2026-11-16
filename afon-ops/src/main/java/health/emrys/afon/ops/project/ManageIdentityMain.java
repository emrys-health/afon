package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.tools.KeyCloakTunnel;
import health.emrys.afon.project.AfonProject;
import health.emrys.afon.project.ProjectException;
import health.emrys.afon.project.FileProjectProvider;
import health.emrys.afon.project.TreProject;
import health.emrys.afon.project.ProjectEnv;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Manages a project's place in the central identity, the one Keycloak that issues the tokens of every project:
 * <pre>
 * manage-identity create &lt;project&gt; [--public-keys &lt;dir&gt;]
 * manage-identity add-member &lt;project&gt; &lt;email&gt; [--first-name &lt;name&gt;] [--last-name &lt;name&gt;]
 * manage-identity remove-member &lt;project&gt; &lt;email&gt;
 * manage-identity sync-members &lt;project&gt;
 * manage-identity close &lt;project&gt;
 * </pre>
 * <ul>
 *   <li>{@code create} makes the project's clients, group and token claims, or converges them; {@code <dir>} holds the
 *       public key, as a JWK, of each of the project's clients, {@code tre.jwk} and {@code git.jwk}, which the TRE operator
 *       gives: the private keys stay with the operator, and the Git client has none if the project has no Git of its own.  It is the {@code keys} dir of the project's own dir if not given,
 *       see {@link FileProjectProvider#keysDir};</li>
 *   <li>{@code add-member} makes the researcher in the central identity if absent, with an invitation email, adds them to the
 *       project's group, and prints their user id, which each Provider whose contract names them makes their principal from;</li>
 *   <li>{@code remove-member} removes the researcher from the group and ends their sessions;</li>
 *   <li>{@code sync-members} makes the project's group have the members that the project's file lists, and no others: it adds those that are
 *       missing, as {@code add-member} does, and removes the others, as {@code remove-member} does.  A file with no {@code members} leaves the
 *       group as it is; one with an empty list empties it;</li>
 *   <li>{@code close} closes the project's TRE client, so that no token is issued for it.</li>
 *   <li>{@code <project>} is the id of the project, whose file the deployer writes in its own dir,
 *       {@code <afon-home>/projects/<project>/project.yaml}, see {@link FileProjectProvider#forProject}.</li>
 * </ul>
 * It runs in the config it is given, whose Keycloak admin credentials are in its {@code secret.properties}, and acts on the Keycloak at the
 * OIDC domain of the project's cloud: when run by {@code manage-project} it is in the project's home, and so in the config that the project is
 * made from, that of its env.
 * A token already issued lasts until it expires.  What acts on the project as a whole is {@code manage-project}.
 */
public class ManageIdentityMain {

    private static final String PUBLIC_KEYS = "--public-keys";
    private static final String FIRST_NAME = "--first-name";
    private static final String LAST_NAME = "--last-name";
    private static final String KUBECONFIG = "--kubeconfig";

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-identity <action> <project> [<args>], where <action> is one of:",
        "  create [" + PUBLIC_KEYS + " <dir>]  makes the project's clients, group and token claims, or converges them;",
        "                                  <dir> holds tre.jwk and git.jwk, the public key of each client: the " + FileProjectProvider.KEYS_DIR + " dir",
        "                                  of the project's own dir if not given",
        "  add-member <email> [" + FIRST_NAME + " <name>] [" + LAST_NAME + " <name>]",
        "                                  makes the researcher if absent, adds them to the project's group, prints their user id",
        "  remove-member <email>           removes the researcher from the group and ends their sessions",
        "  sync-members                    makes the project's group have the members that its project.yaml lists, and no others (none listed: left as it is)",
        "  close                           closes the project's TRE client, so that no token is issued for it",
        "Any action takes " + KUBECONFIG + " <file>: the kubeconfig of the cluster of the central identity, whose Keycloak is then reached through a",
        "  port-forward to its pod, as its ingress may not admit this machine to its admin API.  Without it, that of KEYCLOAK_KUBECONFIG in the",
        "  config, which that of a project that names its identity project has: run in its home, eg manage-project run <project> manage-identity ...;",
        "  else Keycloak is reached at its OIDC domain name, which must admit this machine",
        "<project> is the id of the project, which is left out in the home of the project, whose config has it: manage-project run <project> manage-identity <action>, eg xyz-yyyy-nnnn, whose file the deployer writes in its own dir,",
        "  <afon-home>/" + FileProjectProvider.PROJECTS_DIR + "/<project>/" + FileProjectProvider.FILE);

    /**
     * Opens a tunnel to Keycloak in the cluster of a kubeconfig, and calls with its endpoint, which it closes when the call returns; or calls with
     * no endpoint, ie for Keycloak at its OIDC domain name, if there is no tunnel to open.
     */
    @FunctionalInterface
    interface Tunnel {
        /** @param kubeConfig the kubeconfig of the cluster with Keycloak in it, or {@code null} for the one that the config names, if any */
        void run(AfonCloud cloud, Path kubeConfig, KeyCloakTunnel.Call call) throws Exception;
    }

    /** The identity at the endpoint, ie {@code host:port}, or at its OIDC domain name if the endpoint is {@code null}. */
    private final BiFunction<TreProject, String, IdentityProvider> identity;
    private final Tunnel tunnel;
    /** The id of the project whose home this runs in, or {@code null}: what {@code <project>} is if it is left out. */
    private final java.util.function.Supplier<String> defaultProject;
    private final PrintStream out;
    private final PrintStream err;
    private final Path afonHome;

    ManageIdentityMain(BiFunction<TreProject, String, IdentityProvider> identity, Tunnel tunnel, java.util.function.Supplier<String> defaultProject,
                       PrintStream out, PrintStream err, Path afonHome) {
        this.identity = identity;
        this.tunnel = tunnel;
        this.defaultProject = defaultProject;
        this.out = out;
        this.err = err;
        this.afonHome = afonHome;
    }

    /**
     * Runs the command.
     *
     * @return the exit status: 0 if it succeeded, 1 if it failed, 2 if the usage is wrong
     */
    int run(String[] args) {
        if (args.length == 0 || List.of(args).contains("-h") || List.of(args).contains("--help")) {
            out.println(USAGE);
            return 0;
        }
        Map<String, String> options = new java.util.LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (!List.of(PUBLIC_KEYS, FIRST_NAME, LAST_NAME, KUBECONFIG).contains(args[i]) || i + 1 >= args.length) {
                    return usage("Unknown option, or no value for " + args[i]);
                }
                options.put(args[i], args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        String action = args[0];
        // <project> is left out in the home of a project, whose config has its id
        int withoutProject = switch (action) {
            case "create", "close", "sync-members" -> 0;
            case "add-member", "remove-member" -> 1;
            default -> -1;
        };
        String project;
        List<String> rest;
        if (positional.size() == withoutProject) {
            project = defaultProject.get();
            if (project == null || project.isBlank()) {
                return usage(action + " takes a <project>, unless it is run in the home of one: manage-project run <project> manage-identity " + action);
            }
            rest = positional;
        } else if (positional.isEmpty()) {
            return usage(action + " takes a <project>");
        } else {
            project = positional.get(0);
            rest = positional.subList(1, positional.size());
        }
        return switch (action) {
            case "create" -> rest.isEmpty() ? with(project, options, (p, e) -> create(p, e, options)) : usage("create takes " + PUBLIC_KEYS + " <dir>, and no other argument");
            case "add-member" -> rest.size() == 1 ? with(project, options, (p, e) -> addMember(p, e, rest.get(0), options))
                                                  : usage("add-member takes one <email>");
            case "remove-member" -> rest.size() == 1 ? with(project, options, (p, e) -> removeMember(p, e, rest.get(0)))
                                                     : usage("remove-member takes one <email>");
            case "sync-members" -> rest.isEmpty() ? with(project, options, this::syncMembers) : usage("sync-members takes no argument");
            case "close" -> rest.isEmpty() ? with(project, options, this::close) : usage("close takes no argument");
            default -> usage("Unknown action '" + action + "'");
        };
    }

    private int usage(String problem) {
        err.println(problem);
        err.println(USAGE);
        return 2;
    }

    private interface Action {
        /** @param endpoint where Keycloak is reached, or {@code null} for its OIDC domain name */
        void run(TreProject project, String endpoint) throws IOException;
    }

    /**
     * Loads the project, then runs the action on it, reporting a failure as the status rather than a stack trace.
     */
    private int with(String project, Map<String, String> options, Action action) {
        FileProjectProvider provider;
        try {
            provider = FileProjectProvider.forProject(afonHome, project);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!provider.exists()) {
            err.printf("No project file %s: write the project's %s in its dir%n", provider.file(), FileProjectProvider.FILE);
            return 1;
        }
        try {
            AfonProject loaded = provider.getProject();
            if (!(loaded instanceof TreProject tre)) {
                err.printf("Project %s is the central identity itself: it has no clients, group or members, and its stack is deployed by manage-project up%n", loaded.id());
                return 1;
            }
            Path kubeConfig = options.containsKey(KUBECONFIG) ? Path.of(options.get(KUBECONFIG)) : null;
            if (kubeConfig != null && !Files.isRegularFile(kubeConfig)) {
                err.printf("No kubeconfig %s: write it with the update-kube-config script of the cloud for the central identity's stack%n", kubeConfig);
                return 1;
            }
            tunnel.run(tre.cloud(), kubeConfig, endpoint -> action.run(tre, endpoint));
            return 0;
        } catch (ProjectException | IllegalStateException | IllegalArgumentException | IOException e) {
            err.println(e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 1;
        } catch (Exception e) {
            err.println(e.getMessage() + (e.getCause() == null ? "" : ": " + e.getCause().getMessage()));
            return 1;
        }
    }

    private void create(TreProject project, String endpoint, Map<String, String> options) throws IOException {
        Path dir = options.containsKey(PUBLIC_KEYS) ? Path.of(options.get(PUBLIC_KEYS))
                                                    : FileProjectProvider.keysDir(afonHome, project.id().value());
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("tre", read(dir.resolve("tre.jwk")));
        if (project.git() != null) {
            keys.put("git", read(dir.resolve("git.jwk")));
        }
        identity.apply(project, endpoint).ensureProject(project, keys);
        out.printf("Project %s is made in the central identity: client %s%s, and a group %s%n", project.id(),
                   project.identity().treClient(), project.git() == null ? "" : " and " + project.identity().gitClient(), project.id());
    }

    private static String read(Path key) throws IOException {
        if (!Files.isRegularFile(key)) {
            throw new IOException("No public key " + key + ": the TRE operator gives tre.jwk and git.jwk, to put in the project's " + FileProjectProvider.KEYS_DIR + " dir");
        }
        return Files.readString(key, StandardCharsets.UTF_8);
    }

    private void addMember(TreProject project, String endpoint, String email, Map<String, String> options) {
        IdentityProvider provider = identity.apply(project, endpoint);
        String userId = provider.ensureUser(email, options.get(FIRST_NAME), options.get(LAST_NAME));
        provider.addMember(project, userId);
        out.println(userId);
    }

    private void removeMember(TreProject project, String endpoint, String email) throws IOException {
        IdentityProvider provider = identity.apply(project, endpoint);
        Optional<String> userId = provider.findUser(email);
        if (userId.isEmpty()) {
            throw new IOException("No user " + email + " in the central identity");
        }
        provider.removeMember(project, userId.get());
        out.printf("Removed %s (%s) from project %s, and ended their sessions%n", email, userId.get(), project.id());
    }

    private void syncMembers(TreProject project, String endpoint) {
        if (project.members() == null) {
            out.printf("Project %s lists no members: its group is left as it is%n", project.id());
            return;
        }
        IdentityProvider provider = identity.apply(project, endpoint);
        Map<String, String> current = provider.members(project);
        Map<String, String> wanted = new LinkedHashMap<>();
        for (TreProject.Member member : project.members()) {
            String userId = provider.ensureUser(member.email(), member.firstName(), member.lastName());
            wanted.put(userId, member.email());
            if (!current.containsKey(userId)) {
                provider.addMember(project, userId);
                out.printf("Added %s (%s) to project %s%n", member.email(), userId, project.id());
            }
        }
        current.forEach((userId, email) -> {
            if (!wanted.containsKey(userId)) {
                provider.removeMember(project, userId);
                out.printf("Removed %s (%s) from project %s, and ended their sessions%n", email, userId, project.id());
            }
        });
        out.printf("Project %s has %d member%s%n", project.id(), wanted.size(), wanted.size() == 1 ? "" : "s");
    }

    private void close(TreProject project, String endpoint) {
        identity.apply(project, endpoint).closeProject(project);
        out.printf("Closed the TRE client %s of project %s%n", project.identity().treClient(), project.id());
    }

    /**
     * @return the KeyCloak of the project's cloud, at that cloud's OIDC domain, with the admin credentials of the config that this runs in
     */
    private static IdentityProvider identityOf(TreProject project, String endpoint) {
        OpsPropertyProvider properties = new OpsPropertyProvider();
        return new KeyCloakProvider(properties, endpoint != null ? endpoint : properties.getOidcDomainName(project.cloud()));
    }

    private static void tunnel(AfonCloud cloud, Path kubeConfig, KeyCloakTunnel.Call call) throws Exception {
        OpsPropertyProvider properties = new OpsPropertyProvider();
        if (kubeConfig != null) {
            KeyCloakTunnel.run(cloud, properties, kubeConfig, call);
            return;
        }
        // the cluster of Keycloak that the config of a project names, if any; else it is reached at its OIDC domain name, which must admit this machine
        String configured = properties.getValue(AfonProperty.KEYCLOAK_KUBECONFIG, "");
        if (configured == null || configured.isBlank()) {
            call.run(null);
        } else {
            KeyCloakTunnel.run(cloud, properties, call);
        }
    }

    public static void main(String[] args) {
        int status = new ManageIdentityMain(ManageIdentityMain::identityOf, ManageIdentityMain::tunnel,
                                             () -> new OpsPropertyProvider().getValue(AfonProperty.PROJECT_ID, ""), System.out, System.err,
                                             ProjectEnv.defaultAfonHome()).run(args);
        if (status != 0) {
            System.exit(status);
        }
    }
}
