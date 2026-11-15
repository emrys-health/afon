package health.emrys.afon.ops.project;

import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.project.AfonProject;
import health.emrys.afon.project.ProjectException;
import health.emrys.afon.project.FileProjectProvider;
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
import java.util.function.Function;

/**
 * Manages a project's place in the central identity, the one Keycloak that issues the tokens of every project:
 * <pre>
 * manage-identity create &lt;project&gt; [--public-keys &lt;dir&gt;]
 * manage-identity add-member &lt;project&gt; &lt;email&gt; [--first-name &lt;name&gt;] [--last-name &lt;name&gt;]
 * manage-identity remove-member &lt;project&gt; &lt;email&gt;
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

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-identity <action> <project> [<args>], where <action> is one of:",
        "  create [" + PUBLIC_KEYS + " <dir>]  makes the project's clients, group and token claims, or converges them;",
        "                                  <dir> holds tre.jwk and git.jwk, the public key of each client: the " + FileProjectProvider.KEYS_DIR + " dir",
        "                                  of the project's own dir if not given",
        "  add-member <email> [" + FIRST_NAME + " <name>] [" + LAST_NAME + " <name>]",
        "                                  makes the researcher if absent, adds them to the project's group, prints their user id",
        "  remove-member <email>           removes the researcher from the group and ends their sessions",
        "  close                           closes the project's TRE client, so that no token is issued for it",
        "<project> is the id of the project, eg cvd-2026-0007, whose file the deployer writes in its own dir,",
        "  <afon-home>/" + FileProjectProvider.PROJECTS_DIR + "/<project>/" + FileProjectProvider.FILE);

    private final Function<AfonProject, IdentityProvider> identity;
    private final PrintStream out;
    private final PrintStream err;
    private final Path afonHome;

    ManageIdentityMain(Function<AfonProject, IdentityProvider> identity, PrintStream out, PrintStream err, Path afonHome) {
        this.identity = identity;
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
                if (!List.of(PUBLIC_KEYS, FIRST_NAME, LAST_NAME).contains(args[i]) || i + 1 >= args.length) {
                    return usage("Unknown option, or no value for " + args[i]);
                }
                options.put(args[i], args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        String action = args[0];
        if (positional.isEmpty()) {
            return usage(action + " takes a <project>");
        }
        String project = positional.get(0);
        List<String> rest = positional.subList(1, positional.size());
        return switch (action) {
            case "create" -> rest.isEmpty() ? with(project, p -> create(p, options)) : usage("create takes " + PUBLIC_KEYS + " <dir>, and no other argument");
            case "add-member" -> rest.size() == 1 ? with(project, p -> addMember(p, rest.get(0), options))
                                                  : usage("add-member takes one <email>");
            case "remove-member" -> rest.size() == 1 ? with(project, p -> removeMember(p, rest.get(0)))
                                                     : usage("remove-member takes one <email>");
            case "close" -> rest.isEmpty() ? with(project, this::close) : usage("close takes no argument");
            default -> usage("Unknown action '" + action + "'");
        };
    }

    private int usage(String problem) {
        err.println(problem);
        err.println(USAGE);
        return 2;
    }

    private interface Action {
        void run(AfonProject project) throws IOException;
    }

    /**
     * Loads the project, then runs the action on it, reporting a failure as the status rather than a stack trace.
     */
    private int with(String project, Action action) {
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
            action.run(provider.getProject());
            return 0;
        } catch (ProjectException | IllegalStateException | IllegalArgumentException | IOException e) {
            err.println(e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            err.println(e.getMessage() + (e.getCause() == null ? "" : ": " + e.getCause().getMessage()));
            return 1;
        }
    }

    private void create(AfonProject project, Map<String, String> options) throws IOException {
        Path dir = options.containsKey(PUBLIC_KEYS) ? Path.of(options.get(PUBLIC_KEYS))
                                                    : FileProjectProvider.keysDir(afonHome, project.id().value());
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("tre", read(dir.resolve("tre.jwk")));
        if (project.git() != null) {
            keys.put("git", read(dir.resolve("git.jwk")));
        }
        identity.apply(project).ensureProject(project, keys);
        out.printf("Project %s is made in the central identity: client %s%s, and a group %s%n", project.id(),
                   project.identity().treClient(), project.git() == null ? "" : " and " + project.identity().gitClient(), project.id());
    }

    private static String read(Path key) throws IOException {
        if (!Files.isRegularFile(key)) {
            throw new IOException("No public key " + key + ": the TRE operator gives tre.jwk and git.jwk, to put in the project's " + FileProjectProvider.KEYS_DIR + " dir");
        }
        return Files.readString(key, StandardCharsets.UTF_8);
    }

    private void addMember(AfonProject project, String email, Map<String, String> options) {
        IdentityProvider provider = identity.apply(project);
        String userId = provider.ensureUser(email, options.get(FIRST_NAME), options.get(LAST_NAME));
        provider.addMember(project, userId);
        out.println(userId);
    }

    private void removeMember(AfonProject project, String email) throws IOException {
        IdentityProvider provider = identity.apply(project);
        Optional<String> userId = provider.findUser(email);
        if (userId.isEmpty()) {
            throw new IOException("No user " + email + " in the central identity");
        }
        provider.removeMember(project, userId.get());
        out.printf("Removed %s (%s) from project %s, and ended their sessions%n", email, userId.get(), project.id());
    }

    private void close(AfonProject project) {
        identity.apply(project).closeProject(project);
        out.printf("Closed the TRE client %s of project %s%n", project.identity().treClient(), project.id());
    }

    /**
     * @return the KeyCloak of the project's cloud, at that cloud's OIDC domain, with the admin credentials of the config that this runs in
     */
    private static IdentityProvider identityOf(AfonProject project) {
        OpsPropertyProvider properties = new OpsPropertyProvider();
        return new KeyCloakProvider(properties, properties.getOidcDomainName(project.cloud()));
    }

    public static void main(String[] args) {
        int status = new ManageIdentityMain(ManageIdentityMain::identityOf, System.out, System.err,
                                             ProjectEnv.defaultAfonHome()).run(args);
        if (status != 0) {
            System.exit(status);
        }
    }
}
