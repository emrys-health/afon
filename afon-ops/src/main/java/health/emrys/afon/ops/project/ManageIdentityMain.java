package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.tools.KeyCloakTunnel;
import health.emrys.afon.project.ProjectStack;
import health.emrys.afon.project.ProjectStackException;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.TreProjectStack;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakMemberLogin;
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
 * Manages a project stack's place in the central identity, the one Keycloak that issues the tokens of every project stack:
 * <pre>
 * manage-identity create &lt;project-stack&gt; [--secrets &lt;dir&gt;]
 * manage-identity add-member &lt;project-stack&gt; &lt;email&gt; [--first-name &lt;name&gt;] [--last-name &lt;name&gt;]
 * manage-identity remove-member &lt;project-stack&gt; &lt;email&gt;
 * manage-identity sync-members &lt;project-stack&gt;
 * manage-identity invite &lt;project-stack&gt; &lt;email&gt;
 * manage-identity remove-authenticator &lt;project-stack&gt; &lt;email&gt;
 * manage-identity user-id &lt;project-stack&gt; &lt;email&gt;
 * manage-identity close &lt;project-stack&gt;
 * </pre>
 * <ul>
 *   <li>{@code create} makes the project stack's clients, group and token claims, or converges them; {@code <dir>} holds the
 *       secret of each of the project stack's clients, {@code tre.secret} and {@code git.secret}, which {@code project-secret} makes and
 *       the project stack's config is given too: the Git client has none if the project stack has no Git of its own.  It is the {@code keys} dir of the
 *       project stack's own dir if not given, see {@link FileProjectStackProvider#secretsDir};</li>
 *   <li>{@code add-member} makes the member in the central identity if absent, with an invitation email, adds them to the
 *       project stack's group, and prints their user id, which each Data Provider whose contract names them makes their principal from;</li>
 *   <li>{@code remove-member} removes the member from the group and ends their sessions;</li>
 *   <li>{@code sync-members} makes the project stack's group have the members that the project stack's file lists, and no others: it adds those that are
 *       missing, as {@code add-member} does, and removes the others, as {@code remove-member} does.  A project stack has at least one member, so
 *       the group is never emptied by it;</li>
 *   <li>{@code invite} sends the invitation again to a member who is in the central identity, by email: for one who never got it, let it
 *       expire, or lost their second factor.  Unlike the invitation of {@code add-member}, which is a warning if it cannot be sent, it fails;</li>
 *   <li>{@code close} closes the project stack's TRE client, so that no token is issued for it.</li>
 *   <li>{@code <project-stack>} is the id of the project stack, whose file the deployer writes in its own dir,
 *       {@code <afon-home>/projects/<project-stack>/project.yaml}, see {@link FileProjectStackProvider#forProjectStack}.</li>
 * </ul>
 * It runs in the config it is given, whose Keycloak admin credentials are in its {@code secret.properties}, and acts on the Keycloak at the
 * OIDC domain of the project stack's cloud: when run by {@code manage-project-stack} it is in the project stack's home, and so in the config that the project stack is
 * made from, that of its env.
 * A token already issued lasts until it expires.  What acts on the project stack as a whole is {@code manage-project-stack}.
 */
public class ManageIdentityMain {

    private static final String SECRETS = "--secrets";
    private static final String FIRST_NAME = "--first-name";
    private static final String LAST_NAME = "--last-name";
    private static final String KUBECONFIG = "--kubeconfig";

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-identity <action> <project-stack> [<args>], where <action> is one of:",
        "  create [" + SECRETS + " <dir>]  makes the project stack's clients, group and token claims, or converges them;",
        "                                  <dir> holds tre.secret and git.secret, the secret of each client: the " + FileProjectStackProvider.SECRETS_DIR + " dir",
        "                                  of the project stack's own dir if not given",
        "  add-member <email> [" + FIRST_NAME + " <name>] [" + LAST_NAME + " <name>]",
        "                                  makes the member if absent, adds them to the project stack's group, prints their user id",
        "  remove-member <email>           removes the member from the group and ends their sessions",
        "  sync-members                    makes the project stack's group have the members that its project.yaml lists, and no others",
        "  invite <email>                  sends the invitation again to a member who is in the central identity (it fails if it cannot be sent)",
        "  remove-authenticator <email>    removes the member's authenticators and ends their sessions, so that they enrol a new one, eg from the invitation",
        "  user-id <email>                 prints the user id that the central identity gave the member, which manage-data takes; it changes nothing",
        "  smoke-credentials               gives each of the smokeMembers of project.yaml a password and an authenticator that the tests know, kept in",
        "                                  " + FileProjectStackProvider.SECRETS_DIR + "/" + SmokeMemberCredentials.FILE + "; it refuses one who has an authenticator, who may be a real person",
        "  close                           closes the project stack's TRE client, so that no token is issued for it",
        "Any action takes " + KUBECONFIG + " <file>: the kubeconfig of the cluster of the central identity, whose Keycloak is then reached through a",
        "  port-forward to its pod, as its ingress may not admit this machine to its admin API.  Without it, that of KEYCLOAK_KUBECONFIG in the",
        "  config, which that of a project stack that names its identity project stack has: run in its home, eg manage-project-stack run <project-stack> manage-identity ...;",
        "  else Keycloak is reached at its OIDC domain name, which must admit this machine",
        "<project-stack> is the id of the project stack, which is left out in the home of the project stack, whose config has it: manage-project-stack run <project-stack> manage-identity <action>, eg xyz-yyyy-nnnn, whose file the deployer writes in its own dir,",
        "  <afon-home>/" + FileProjectStackProvider.PROJECTS_DIR + "/<project-stack>/" + FileProjectStackProvider.FILE);

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
    private final BiFunction<TreProjectStack, String, IdentityProvider> identity;
    private final Tunnel tunnel;
    /** The id of the project stack whose home this runs in, or {@code null}: what {@code <project-stack>} is if it is left out. */
    private final java.util.function.Supplier<String> defaultProjectStack;
    private final PrintStream out;
    private final PrintStream err;
    private final Path afonHome;
    /** The login to a realm, at its issuer URL, as a member signs in to it. */
    private final java.util.function.Function<java.net.URI, KeyCloakMemberLogin> memberLogin;

    ManageIdentityMain(BiFunction<TreProjectStack, String, IdentityProvider> identity, Tunnel tunnel, java.util.function.Supplier<String> defaultProjectStack,
                       PrintStream out, PrintStream err, Path afonHome) {
        this(identity, tunnel, defaultProjectStack, out, err, afonHome, KeyCloakMemberLogin::new);
    }

    ManageIdentityMain(BiFunction<TreProjectStack, String, IdentityProvider> identity, Tunnel tunnel, java.util.function.Supplier<String> defaultProjectStack,
                       PrintStream out, PrintStream err, Path afonHome, java.util.function.Function<java.net.URI, KeyCloakMemberLogin> memberLogin) {
        this.memberLogin = memberLogin;
        this.identity = identity;
        this.tunnel = tunnel;
        this.defaultProjectStack = defaultProjectStack;
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
                if (!List.of(SECRETS, FIRST_NAME, LAST_NAME, KUBECONFIG).contains(args[i]) || i + 1 >= args.length) {
                    return usage("Unknown option, or no value for " + args[i]);
                }
                options.put(args[i], args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        String action = args[0];
        // <project-stack> is left out in the home of a project stack, whose config has its id
        int withoutProject = switch (action) {
            case "create", "close", "sync-members", "smoke-credentials" -> 0;
            case "add-member", "remove-member", "invite", "remove-authenticator", "user-id" -> 1;
            default -> -1;
        };
        String projectStack;
        List<String> rest;
        if (positional.size() == withoutProject) {
            projectStack = defaultProjectStack.get();
            if (projectStack == null || projectStack.isBlank()) {
                return usage(action + " takes a <project-stack>, unless it is run in the home of one: manage-project-stack run <project-stack> manage-identity " + action);
            }
            rest = positional;
        } else if (positional.isEmpty()) {
            return usage(action + " takes a <project-stack>");
        } else {
            projectStack = positional.get(0);
            rest = positional.subList(1, positional.size());
        }
        return switch (action) {
            case "create" -> rest.isEmpty() ? with(projectStack, options, (p, e) -> create(p, e, options)) : usage("create takes " + SECRETS + " <dir>, and no other argument");
            case "add-member" -> rest.size() == 1 ? with(projectStack, options, (p, e) -> addMember(p, e, rest.get(0), options))
                                                  : usage("add-member takes one <email>");
            case "remove-member" -> rest.size() == 1 ? with(projectStack, options, (p, e) -> removeMember(p, e, rest.get(0)))
                                                     : usage("remove-member takes one <email>");
            case "invite" -> rest.size() == 1 ? with(projectStack, options, (p, e) -> invite(p, e, rest.get(0))) : usage("invite takes one <email>");
            case "sync-members" -> rest.isEmpty() ? with(projectStack, options, this::syncMembers) : usage("sync-members takes no argument");
            case "remove-authenticator" -> rest.size() == 1 ? with(projectStack, options, (p, e) -> removeAuthenticator(p, e, rest.get(0)))
                                                           : usage("remove-authenticator takes one <email>");
            case "user-id" -> rest.size() == 1 ? with(projectStack, options, (p, e) -> userId(p, e, rest.get(0))) : usage("user-id takes one <email>");
            case "smoke-credentials" -> rest.isEmpty() ? with(projectStack, options, this::smokeCredentials) : usage("smoke-credentials takes no argument");
            case "close" -> rest.isEmpty() ? with(projectStack, options, this::close) : usage("close takes no argument");
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
        void run(TreProjectStack projectStack, String endpoint) throws IOException;
    }

    /**
     * Loads the project stack, then runs the action on it, reporting a failure as the status rather than a stack trace.
     */
    private int with(String projectStack, Map<String, String> options, Action action) {
        FileProjectStackProvider provider;
        try {
            provider = FileProjectStackProvider.forProjectStack(afonHome, projectStack);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!provider.exists()) {
            err.printf("No project stack file %s: write the project stack's %s in its dir%n", provider.file(), FileProjectStackProvider.FILE);
            return 1;
        }
        try {
            ProjectStack loaded = provider.getProjectStack();
            if (!(loaded instanceof TreProjectStack tre)) {
                err.printf("Project stack %s has the role %s: it has no clients, group or members, and its stack is deployed by manage-project-stack up%n", loaded.id(),
                           loaded.role());
                return 1;
            }
            Path kubeConfig = options.containsKey(KUBECONFIG) ? Path.of(options.get(KUBECONFIG)) : null;
            if (kubeConfig != null && !Files.isRegularFile(kubeConfig)) {
                err.printf("No kubeconfig %s: write it with the update-kube-config script of the cloud for the central identity's stack%n", kubeConfig);
                return 1;
            }
            tunnel.run(tre.cloud(), kubeConfig, endpoint -> action.run(tre, endpoint));
            return 0;
        } catch (ProjectStackException | IllegalStateException | IllegalArgumentException | IOException e) {
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

    private void create(TreProjectStack projectStack, String endpoint, Map<String, String> options) throws IOException {
        Path dir = options.containsKey(SECRETS) ? Path.of(options.get(SECRETS))
                                                    : FileProjectStackProvider.secretsDir(afonHome, projectStack.id().value());
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("tre", read(dir.resolve("tre.secret")));
        if (projectStack.git() != null) {
            secrets.put("git", read(dir.resolve("git.secret")));
        }
        identity.apply(projectStack, endpoint).ensureProject(projectStack, secrets);
        out.printf("Project stack %s is made in the central identity: client %s%s, and a group %s%n", projectStack.id(),
                   projectStack.identity().treClient(), projectStack.git() == null ? "" : " and " + projectStack.identity().gitClient(), projectStack.id());
    }

    /**
     * Gives each smoke member that the file lists a password and an authenticator that the tests know: it sets the password, and signs the member in to the
     * project stack's TRE client, which walks them through enrolling an authenticator that the login answers, as a member does with a phone, keeping the secret
     * that the page shows.  It is for members made for the tests: it does nothing to one who has an authenticator that it did not make, who may be a real person.
     */
    private void smokeCredentials(TreProjectStack projectStack, String endpoint) throws IOException {
        if (projectStack.smokeMembers().isEmpty()) {
            throw new IOException("The file of " + projectStack.id() + " lists no smokeMembers: list the members that the smoke tests sign in as there, and sync-members");
        }
        for (TreProjectStack.Member member : projectStack.smokeMembers()) {
            smokeCredentials(projectStack, endpoint, member.email(), member.firstName(), member.lastName());
        }
    }

    private void smokeCredentials(TreProjectStack projectStack, String endpoint, String email, String firstName, String lastName) throws IOException {
        Path dir = FileProjectStackProvider.secretsDir(afonHome, projectStack.id().value());
        if (SmokeMemberCredentials.read(dir, email).isPresent()) {
            out.printf("%s has the credentials of the smoke tests already, in %s%n", email, dir.resolve(SmokeMemberCredentials.FILE));
            return;
        }
        IdentityProvider provider = identity.apply(projectStack, endpoint);
        if (provider.findUser(email).isEmpty()) {
            throw new IOException("No user " + email + " in the central identity: up makes the smoke members, in its register stage, or sync-members does");
        }
        if (provider.hasSecondFactor(email)) {
            throw new IOException(email + " has an authenticator that this did not make, and may be a real person's, so it is left as it is: list a member made for the tests "
                                  + "in smokeMembers, or, if this one is, remove-authenticator first");
        }
        String client = projectStack.identity().treClient();
        String redirectUri = provider.redirectUrisOf(client).stream().filter(uri -> uri.endsWith("/hub/oauth_callback")).findFirst().orElseThrow(
            () -> new IOException("The client " + client + " has no redirect URI for JupyterHub's callback in the central identity: create makes it"));
        String password = SmokeMemberCredentials.randomPassword();
        provider.setPassword(email, password, firstName == null ? "Smoke" : firstName, lastName == null ? "Tests" : lastName);
        KeyCloakMemberLogin.Token token;
        try {
            token = memberLogin.apply(java.net.URI.create(projectStack.identity().issuerUrl()))
                               .login(client, read(dir.resolve("tre.secret")), redirectUri, email, password, null,
                                      // kept as soon as the realm has the authenticator, as nothing else knows its secret if the login then fails
                                      enrolled -> keep(new SmokeMemberCredentials(email, password, enrolled), dir));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while signing " + email + " in", e);
        }
        new SmokeMemberCredentials(email, password, token.totpSecret()).write(dir);
        out.printf("Gave %s a password and an authenticator, kept in %s: signing in to %s gave a token with the project %s and the acr %s%n", email,
                   dir.resolve(SmokeMemberCredentials.FILE), client, token.claims().path(projectStack.identity().projectClaim()), token.claims().path("acr").asText());
    }

    private void keep(SmokeMemberCredentials credentials, Path dir) {
        try {
            credentials.write(dir);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String read(Path secret) throws IOException {
        if (!Files.isRegularFile(secret)) {
            throw new IOException("No secret " + secret + ": make it with project-secret, which puts tre.secret and git.secret in the project stack's " + FileProjectStackProvider.SECRETS_DIR + " dir");
        }
        return Files.readString(secret, StandardCharsets.UTF_8).strip();
    }

    private void addMember(TreProjectStack projectStack, String endpoint, String email, Map<String, String> options) {
        IdentityProvider provider = identity.apply(projectStack, endpoint);
        String userId = provider.ensureUser(email, options.get(FIRST_NAME), options.get(LAST_NAME), projectStack.identity().treClient());
        provider.addMember(projectStack, userId);
        record(projectStack, () -> ProjectMembers.add(ProjectMembers.file(afonHome, projectStack.id().value()), userId, email.toLowerCase(java.util.Locale.ROOT)));
        if (projectStack.allMembers().stream().noneMatch(member -> member.email().equalsIgnoreCase(email))) {
            err.printf("Note: %s is not listed in the members or smokeMembers of %s: the next sync-members, and so up, takes them out of the group. List them there to keep them.%n",
                       email, projectStack.id());
        }
        out.println(userId);
    }

    private void removeMember(TreProjectStack projectStack, String endpoint, String email) throws IOException {
        IdentityProvider provider = identity.apply(projectStack, endpoint);
        Optional<String> userId = provider.findUser(email);
        if (userId.isEmpty()) {
            throw new IOException("No user " + email + " in the central identity");
        }
        provider.removeMember(projectStack, userId.get());
        record(projectStack, () -> ProjectMembers.remove(ProjectMembers.file(afonHome, projectStack.id().value()), userId.get()));
        out.printf("Removed %s (%s) from project stack %s, and ended their sessions%n", email, userId.get(), projectStack.id());
    }

    /**
     * Prints the user id of the member alone, one line, so that a script can take it: the immutable id that the central identity gave them, which is what
     * {@code manage-data add-member} and the members file hold.  It makes nothing, unlike {@code add-member}.
     */
    private void userId(TreProjectStack projectStack, String endpoint, String email) {
        String userId = identity.apply(projectStack, endpoint).findUser(email).orElseThrow(() -> new IllegalStateException(
            "No user " + email + " in the central identity: add-member makes them, and sends the invitation"));
        out.println(userId);
    }

    private void removeAuthenticator(TreProjectStack projectStack, String endpoint, String email) {
        int removed = identity.apply(projectStack, endpoint).removeSecondFactor(email);
        if (removed == 0) {
            out.printf("%s has no authenticator: nothing is removed%n", email);
        } else {
            out.printf("Removed %d authenticator%s of %s, and ended their sessions: they enrol a new one from the invitation, "
                       + "afon-cli.sh manage-project-stack run %s manage-identity invite %s%n", removed, removed == 1 ? "" : "s", email, projectStack.id(), email);
        }
    }

    private void invite(TreProjectStack projectStack, String endpoint, String email) {
        identity.apply(projectStack, endpoint).invite(email, projectStack.identity().treClient());
        out.printf("Sent the invitation to %s%n", email);
    }

    private void syncMembers(TreProjectStack projectStack, String endpoint) {
        IdentityProvider provider = identity.apply(projectStack, endpoint);
        Map<String, String> current = provider.members(projectStack);
        Map<String, String> wanted = new LinkedHashMap<>();
        for (TreProjectStack.Member member : projectStack.allMembers()) {
            String userId = provider.ensureUser(member.email(), member.firstName(), member.lastName(), projectStack.identity().treClient());
            wanted.put(userId, member.email());
            if (!current.containsKey(userId)) {
                provider.addMember(projectStack, userId);
                out.printf("Added %s (%s) to project stack %s%n", member.email(), userId, projectStack.id());
            }
        }
        current.forEach((userId, email) -> {
            if (!wanted.containsKey(userId)) {
                provider.removeMember(projectStack, userId);
                out.printf("Removed %s (%s) from project stack %s, and ended their sessions%n", email, userId, projectStack.id());
            }
        });
        record(projectStack, () -> ProjectMembers.write(ProjectMembers.file(afonHome, projectStack.id().value()), wanted));
        out.printf("Project stack %s has %d member%s%n", projectStack.id(), wanted.size(), wanted.size() == 1 ? "" : "s");
    }

    @FunctionalInterface
    private interface Recording {
        void run() throws IOException;
    }

    /**
     * Keeps the project stack's members file as the group is: what needs the members' ids reads it, rather than have them copied by hand.  The group in the identity is
     * what changed, so a file that cannot be written is a warning, not a failure.
     */
    private void record(TreProjectStack projectStack, Recording recording) {
        try {
            recording.run();
        } catch (IOException e) {
            err.printf("Could not write the members file %s of project stack %s: %s%n", ProjectMembers.file(afonHome, projectStack.id().value()), projectStack.id(), e.getMessage());
        }
    }

    private void close(TreProjectStack projectStack, String endpoint) {
        identity.apply(projectStack, endpoint).closeProject(projectStack);
        out.printf("Closed the TRE client %s of project stack %s%n", projectStack.identity().treClient(), projectStack.id());
    }

    /**
     * @return the KeyCloak of the project stack's cloud, at that cloud's OIDC domain, with the admin credentials of the config that this runs in
     */
    private static IdentityProvider identityOf(TreProjectStack projectStack, String endpoint) {
        OpsPropertyProvider properties = new OpsPropertyProvider();
        return new KeyCloakProvider(properties, endpoint != null ? endpoint : properties.getOidcDomainName(projectStack.cloud()));
    }

    private static void tunnel(AfonCloud cloud, Path kubeConfig, KeyCloakTunnel.Call call) throws Exception {
        OpsPropertyProvider properties = new OpsPropertyProvider();
        if (kubeConfig != null) {
            KeyCloakTunnel.run(cloud, properties, kubeConfig, call);
            return;
        }
        // the cluster of Keycloak that the config of a project stack names, if any; else it is reached at its OIDC domain name, which must admit this machine
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
                                             ProjectStackEnv.defaultAfonHome()).run(args);
        if (status != 0) {
            System.exit(status);
        }
    }
}
