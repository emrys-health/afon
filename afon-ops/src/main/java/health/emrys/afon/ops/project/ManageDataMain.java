package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.project.TreProjectStack;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.tools.PolarisAccess;
import health.emrys.afon.polaris.config.PolarisEndpoint;
import health.emrys.afon.polaris.config.PolarisProjectGrants;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

/**
 * What a Data Provider grants the project stacks that read its catalogs, in its own Polaris, with the admin credentials of its own config:
 * <pre>
 * manage-data grant &lt;project-stack&gt; &lt;catalog&gt; [--members &lt;file&gt;] [--namespace &lt;namespace&gt;]
 * manage-data add-member &lt;project-stack&gt; &lt;user id&gt;
 * manage-data remove-member &lt;project-stack&gt; &lt;user id&gt;
 * manage-data revoke &lt;project-stack&gt; &lt;catalog&gt;
 * manage-data release &lt;catalog&gt; &lt;table&gt; [--namespace &lt;namespace&gt;]
 * manage-data show [&lt;project-stack&gt;]
 * </pre>
 * <ul>
 *   <li>{@code grant} lets the project stack read the namespace of the catalog, {@code released} unless another is given, read-only, and makes its members
 *       exactly those that the file lists, one user id of the central identity for each line: those that were not are given the project stack's role, and
 *       those that were and are not listed lose it.  The id is the one of the member in the central identity.  The file is, if none is given, the
 *       project stack's own members file, {@code members.csv} in its dir, which {@code manage-identity} writes as it adds and removes the members, so no id is copied
 *       by hand: a line is {@code <user id>} or {@code <user id>,<email>}.  It is made again without harm;</li>
 *   <li>{@code add-member} and {@code remove-member} change one member of a project stack that has been granted;</li>
 *   <li>{@code revoke} ends the project stack's access to the catalog: eg at the end date of its contract;</li>
 *   <li>{@code release} moves a table, that was made and checked in {@code staging}, to the namespace that the project stacks read;</li>
 *   <li>{@code show} reads, and changes nothing: what each catalog holds, and what each project stack, or the one named, has been granted: its principal role, its
 *       members, and its access in each catalog.</li>
 * </ul>
 * {@code <project-stack>} is the id of the TRE project stack that reads the catalog, which the project claim of its tokens names.  It runs in the home of a Data Provider's
 * project stack, in its cloud: {@code manage-project-stack run <provider> manage-data ...}, as it is that Data Provider that grants, with its own credentials.
 */
public class ManageDataMain {

    private static final String MEMBERS = "--members";
    private static final String NAMESPACE = "--namespace";

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-data <action> [<args>], where <action> is one of:",
        "  grant <project-stack> <catalog> [" + MEMBERS + " <file>] [" + NAMESPACE + " <namespace>]",
        "                              lets the project stack read the catalog's namespace (" + PolarisProjectGrants.RELEASED + " if not given), read-only, and",
        "                              makes its members those whose user ids the file lists, one for each line, or <user id>,<email>: the file is, if",
        "                              not given, the " + ProjectMembers.FILE + " of the project stack's dir, which manage-identity keeps",
        "  add-member <project-stack> <user id>     lets one more member read what the project stack reads",
        "  remove-member <project-stack> <user id>  takes the project stack's role from the member",
        "  revoke <project-stack> <catalog>             ends the project stack's access to the catalog",
        "  show [<project-stack>]            shows, and changes nothing: what each catalog holds, and what each project stack, or the one named, has been granted:",
        "                              its principal role, its members (by user id, with their email if the project stack's dir has it), and its access in each catalog",
        "  release <catalog> <table> [" + NAMESPACE + " <namespace>]",
        "                              moves the table from " + PolarisProjectGrants.STAGING + " to the namespace that the project stacks read, unless it is there already",
        "<project-stack> is the id of the TRE project stack that reads the catalog.  It is run in the home of a Data Provider's project stack, in its cloud:",
        "  manage-project-stack run <provider> manage-data <action> ..., with the Polaris admin credentials of that project stack's config");

    private final Supplier<PolarisProjectGrants> grants;
    private final PrintStream out;
    private final PrintStream err;
    /** The Afon home that the project stacks are in, whose dirs hold their members files. */
    private final Path afonHome;
    /** The emails that a TRE project stack's file lists as its members, by the id of the project stack, or {@code null} if it is not a project stack of this Afon home. */
    private final Function<String, List<String>> listedMembers;

    ManageDataMain(Supplier<PolarisProjectGrants> grants, PrintStream out, PrintStream err) {
        this(grants, out, err, ProjectStackEnv.defaultAfonHome());
    }

    ManageDataMain(Supplier<PolarisProjectGrants> grants, PrintStream out, PrintStream err, Path afonHome) {
        this(grants, out, err, afonHome, projectStack -> listedMembersOf(afonHome, projectStack));
    }

    ManageDataMain(Supplier<PolarisProjectGrants> grants, PrintStream out, PrintStream err, Path afonHome, Function<String, List<String>> listedMembers) {
        this.grants = grants;
        this.out = out;
        this.err = err;
        this.afonHome = afonHome;
        this.listedMembers = listedMembers;
    }

    /**
     * @return the emails that the file of the TRE project stack lists as its members, or {@code null} if the Afon home has no such project stack, eg a TRE that Afon did not deploy
     */
    private static List<String> listedMembersOf(Path afonHome, String projectStack) {
        FileProjectStackProvider provider = FileProjectStackProvider.forProjectStack(afonHome, projectStack);
        if (!provider.exists()) {
            return null;
        }
        return provider.getProjectStack() instanceof TreProjectStack tre ? tre.allMembers().stream().map(TreProjectStack.Member::email).toList() : null;
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
                if (!List.of(MEMBERS, NAMESPACE).contains(args[i]) || i + 1 >= args.length) {
                    return usage("Unknown option, or no value for " + args[i]);
                }
                options.put(args[i], args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        String namespace = options.getOrDefault(NAMESPACE, PolarisProjectGrants.RELEASED);
        try {
            return switch (args[0]) {
                case "grant" -> positional.size() == 2
                    ? grant(positional.get(0), positional.get(1), namespace,
                            options.containsKey(MEMBERS) ? Path.of(options.get(MEMBERS)) : ProjectMembers.file(afonHome, positional.get(0)), !options.containsKey(MEMBERS))
                    : usage("grant takes a <project-stack>, a <catalog>, and " + MEMBERS + " <file> if the project stack has no " + ProjectMembers.FILE);
                case "add-member" -> positional.size() == 2 && options.isEmpty() ? addMember(positional.get(0), positional.get(1))
                                                                                       : usage("add-member takes a <project-stack> and a <user id>");
                case "remove-member" -> positional.size() == 2 && options.isEmpty() ? removeMember(positional.get(0), positional.get(1))
                                                                                          : usage("remove-member takes a <project-stack> and a <user id>");
                case "revoke" -> positional.size() == 2 && options.isEmpty() ? revoke(positional.get(0), positional.get(1))
                                                                               : usage("revoke takes a <project-stack> and a <catalog>");
                case "release" -> positional.size() == 2 && !options.containsKey(MEMBERS) ? release(positional.get(0), positional.get(1), namespace)
                                                                                                : usage("release takes a <catalog> and a <table>");
                case "show" -> positional.size() <= 1 && options.isEmpty() ? show(positional.isEmpty() ? null : positional.get(0))
                                                                              : usage("show takes at most a <project-stack>");
                default -> usage("Unknown action '" + args[0] + "'");
            };
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println(e.getMessage() + (e.getCause() == null ? "" : ": " + e.getCause().getMessage()));
            return 1;
        }
    }

    private int usage(String problem) {
        err.println(problem);
        err.println(USAGE);
        return 2;
    }

    private int grant(String projectStack, String catalog, String namespace, Path file, boolean ofTheProject) throws Exception {
        List<String> userIds = members(file);
        if (ofTheProject) {
            checkAgainstTheProjectFile(projectStack, file);
        }
        PolarisProjectGrants.Members changed = grants.get().grant(projectStack, catalog, namespace, userIds);
        out.printf("Project stack %s may read %s.%s, read-only, and has %d member(s)%n", projectStack, catalog, namespace, userIds.size());
        changed.added().forEach(principal -> out.printf("Added %s%n", principal));
        changed.removed().forEach(principal -> out.printf("Removed %s%n", principal));
        return 0;
    }

    private int addMember(String projectStack, String userId) throws Exception {
        grants.get().addMember(projectStack, userId);
        out.printf("Added %s to project stack %s%n", PolarisProjectGrants.memberPrincipal(userId), projectStack);
        return 0;
    }

    private int removeMember(String projectStack, String userId) throws Exception {
        grants.get().removeMember(projectStack, userId);
        out.printf("Removed %s from project stack %s%n", PolarisProjectGrants.memberPrincipal(userId), projectStack);
        return 0;
    }

    private int revoke(String projectStack, String catalog) throws Exception {
        List<String> removed = grants.get().revoke(projectStack, catalog);
        out.printf("Project stack %s may no longer read %s; %d member(s) lost its role%n", projectStack, catalog, removed.size());
        return 0;
    }

    private int show(String projectStack) throws Exception {
        PolarisProjectGrants.Overview overview = grants.get().show(projectStack);
        out.println("Catalogs:");
        if (overview.catalogs().isEmpty()) {
            out.println("  none");
        }
        for (PolarisProjectGrants.CatalogContents catalog : overview.catalogs()) {
            StringBuilder namespaces = new StringBuilder();
            catalog.namespaces().forEach((namespace, tables) -> namespaces.append(namespaces.length() == 0 ? "" : "; ").append(namespace).append(' ').append(tables));
            out.printf("  %s: %s%n", catalog.catalog(), namespaces.length() == 0 ? "no namespaces" : namespaces);
        }
        if (overview.projectStacks().isEmpty()) {
            out.println(projectStack == null ? "No project stack has been granted anything here" : "Project stack " + projectStack + " has been granted nothing here: it has no principal role");
        }
        for (PolarisProjectGrants.ProjectAccess access : overview.projectStacks()) {
            Map<String, String> emails = ProjectMembers.read(ProjectMembers.file(afonHome, access.projectStack()));
            out.printf("Project stack %s (principal role %s)%n", access.projectStack(), access.principalRole());
            out.printf("  members (%d): %s%n", access.principals().size(), access.principals().isEmpty() ? "none" : access.principals().stream()
                .map(principal -> {
                    String id = principal.startsWith(PolarisProjectGrants.PRINCIPAL_PREFIX) ? principal.substring(PolarisProjectGrants.PRINCIPAL_PREFIX.length()) : principal;
                    String email = emails.get(id);
                    return principal + (email == null || email.isEmpty() ? "" : " (" + email + ")");
                }).collect(Collectors.joining(", ")));
            if (access.catalogs().isEmpty()) {
                out.println("  no access in any catalog");
            }
            for (PolarisProjectGrants.CatalogAccess catalog : access.catalogs()) {
                out.printf("  %s: catalog role %s, %s%n", catalog.catalog(), catalog.catalogRole(),
                           catalog.access().isEmpty() ? "no privileges" : String.join("; ", catalog.access()));
            }
        }
        return 0;
    }

    private int release(String catalog, String table, String namespace) throws Exception {
        if (grants.get().release(catalog, table, namespace)) {
            out.printf("Moved %s of %s from %s to %s%n", table, catalog, PolarisProjectGrants.STAGING, namespace);
        } else {
            out.printf("%s of %s is in %s already, and not in %s: nothing to move%n", table, catalog, namespace, PolarisProjectGrants.STAGING);
        }
        return 0;
    }

    /**
     * The members file of the project stack is kept as its group is, and the group is made from the members that the project stack's file lists, which is the authority:
     * a grant from a file that differs from it would give access to someone the project stack does not list, or leave out someone it does, so it is refused.
     *
     * @throws IllegalStateException if they differ
     */
    private void checkAgainstTheProjectFile(String projectStack, Path file) throws IOException {
        List<String> listed = listedMembers.apply(projectStack);
        if (listed == null) {
            return;
        }
        ProjectMembers.Drift drift = ProjectMembers.drift(ProjectMembers.read(file), listed);
        if (!drift.none()) {
            throw new IllegalStateException(String.format(
                "The members file %s is not the members of project stack %s, whose file lists them:%s%s%nMake it so, in the project stack's home: manage-project-stack run %s manage-identity sync-members "
                + "(the file lists the members, and the group and the members file are made from it)",
                file, projectStack,
                drift.missing().isEmpty() ? "" : String.format("%n  not in the members file: %s", String.join(", ", drift.missing())),
                drift.extra().isEmpty() ? "" : String.format("%n  in the members file, not listed: %s", String.join(", ", drift.extra())),
                projectStack));
        }
    }

    /**
     * @return the user ids that the file lists, one for each line, alone or before a comma and the email, without a blank line, a comment (a line that starts
     * with {@code #}) or a repeat
     * @throws IOException if the file cannot be read, or lists none: a project stack of no member is granted by {@code revoke}, not by an empty file
     */
    static List<String> members(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("No file " + file + " of the members: one user id for each line, or <user id>,<email>; for a project stack that has been made in the "
                                  + "identity, manage-identity writes it as it adds the members (sync-members, or up, which runs it)");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.strip();
            // a line is the user id alone, or the user id and the email, as the members file of the project stack has it
            String id = (text.contains(",") ? text.substring(0, text.indexOf(',')) : text).strip();
            if (!id.isEmpty() && !text.startsWith("#")) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new IOException("The file " + file + " lists no member: to end a project stack's access, use revoke");
        }
        return List.copyOf(ids);
    }

    /**
     * @param open what is opened to reach Polaris, which the caller closes when the command is done
     */
    private static PolarisProjectGrants ofTheProviderInThisHome(List<AutoCloseable> open) throws Exception {
        String name = ConfigUtils.afonCloud();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("No cloud: run it in the home of a Data Provider's project stack, manage-project-stack run <provider> manage-data ..., "
                                            + "or give --cloud to afon-cli.sh");
        }
        AfonCloud cloud = AfonCloud.valueOf(name.trim().toUpperCase(Locale.ROOT));
        // as seen by the cloud whose Polaris it calls, at its endpoint
        AfonPropertyProvider properties = new OpsPropertyProvider().forCloud(cloud);
        PolarisAccess access = PolarisAccess.open(properties, cloud);
        open.add(access);
        properties.setValue(POLARIS_ENDPOINT, access.endpoint() != null ? access.endpoint() : new PolarisEndpoint(cloud, properties).getEndpoint());
        return new PolarisProjectGrants(properties);
    }

    public static void main(String[] args) {
        List<AutoCloseable> open = new ArrayList<>();
        int status;
        try {
            status = new ManageDataMain(() -> {
                try {
                    return ofTheProviderInThisHome(open);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }, System.out, System.err).run(args);
        } finally {
            // the tunnel first, then the client that it is made through
            for (int i = open.size() - 1; i >= 0; i--) {
                try {
                    open.get(i).close();
                } catch (Exception e) {
                    // closing is not what the command was asked for
                }
            }
        }
        if (status != 0) {
            System.exit(status);
        }
    }
}
