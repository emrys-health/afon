package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.project.FileProjectProvider;
import health.emrys.afon.project.ProjectEnv;
import health.emrys.afon.project.TreProject;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import health.emrys.afon.ops.steps.StepOperations;
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

import static health.emrys.afon.config.AfonProperty.POLARIS_ACCESS;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

/**
 * What a Data Provider grants the projects that read its catalogs, in its own Polaris, with the admin credentials of its own config:
 * <pre>
 * manage-project-grants grant &lt;project&gt; &lt;catalog&gt; [--members &lt;file&gt;] [--namespace &lt;namespace&gt;]
 * manage-project-grants add-member &lt;project&gt; &lt;user id&gt;
 * manage-project-grants remove-member &lt;project&gt; &lt;user id&gt;
 * manage-project-grants revoke &lt;project&gt; &lt;catalog&gt;
 * manage-project-grants release &lt;catalog&gt; &lt;table&gt; [--namespace &lt;namespace&gt;]
 * </pre>
 * <ul>
 *   <li>{@code grant} lets the project read the namespace of the catalog, {@code released} unless another is given, read-only, and makes its members
 *       exactly those that the file lists, one user id of the central identity for each line: those that were not are given the project's role, and
 *       those that were and are not listed lose it.  The id is the one of the member in the central identity.  The file is, if none is given, the
 *       project's own members file, {@code members.csv} in its dir, which {@code manage-identity} writes as it adds and removes the members, so no id is copied
 *       by hand: a line is {@code <user id>} or {@code <user id>,<email>}.  It is made again without harm;</li>
 *   <li>{@code add-member} and {@code remove-member} change one member of a project that has been granted;</li>
 *   <li>{@code revoke} ends the project's access to the catalog: eg at the end date of its contract;</li>
 *   <li>{@code release} moves a table, that was made and checked in {@code staging}, to the namespace that the projects read.</li>
 * </ul>
 * {@code <project>} is the id of the TRE project that reads the catalog, which the project claim of its tokens names.  It runs in the home of a Data Provider's
 * project, in its cloud: {@code manage-project run <provider> manage-project-grants ...}, as it is that Data Provider that grants, with its own credentials.
 */
public class ManageProjectGrantsMain {

    private static final String MEMBERS = "--members";
    private static final String NAMESPACE = "--namespace";

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: manage-project-grants <action> [<args>], where <action> is one of:",
        "  grant <project> <catalog> [" + MEMBERS + " <file>] [" + NAMESPACE + " <namespace>]",
        "                              lets the project read the catalog's namespace (" + PolarisProjectGrants.RELEASED + " if not given), read-only, and",
        "                              makes its members those whose user ids the file lists, one for each line, or <user id>,<email>: the file is, if",
        "                              not given, the " + ProjectMembers.FILE + " of the project's dir, which manage-identity keeps",
        "  add-member <project> <user id>     lets one more member read what the project reads",
        "  remove-member <project> <user id>  takes the project's role from the member",
        "  revoke <project> <catalog>             ends the project's access to the catalog",
        "  release <catalog> <table> [" + NAMESPACE + " <namespace>]",
        "                              moves the table from " + PolarisProjectGrants.STAGING + " to the namespace that the projects read",
        "<project> is the id of the TRE project that reads the catalog.  It is run in the home of a Data Provider's project, in its cloud:",
        "  manage-project run <provider> manage-project-grants <action> ..., with the Polaris admin credentials of that project's config");

    private final Supplier<PolarisProjectGrants> grants;
    private final PrintStream out;
    private final PrintStream err;
    /** The Afon home that the projects are in, whose dirs hold their members files. */
    private final Path afonHome;
    /** The emails that a TRE project's file lists as its members, by the id of the project, or {@code null} if it is not a project of this Afon home. */
    private final Function<String, List<String>> listedMembers;

    ManageProjectGrantsMain(Supplier<PolarisProjectGrants> grants, PrintStream out, PrintStream err) {
        this(grants, out, err, ProjectEnv.defaultAfonHome());
    }

    ManageProjectGrantsMain(Supplier<PolarisProjectGrants> grants, PrintStream out, PrintStream err, Path afonHome) {
        this(grants, out, err, afonHome, project -> listedMembersOf(afonHome, project));
    }

    ManageProjectGrantsMain(Supplier<PolarisProjectGrants> grants, PrintStream out, PrintStream err, Path afonHome, Function<String, List<String>> listedMembers) {
        this.grants = grants;
        this.out = out;
        this.err = err;
        this.afonHome = afonHome;
        this.listedMembers = listedMembers;
    }

    /**
     * @return the emails that the file of the TRE project lists as its members, or {@code null} if the Afon home has no such project, eg a TRE that Afon did not deploy
     */
    private static List<String> listedMembersOf(Path afonHome, String project) {
        FileProjectProvider provider = FileProjectProvider.forProject(afonHome, project);
        if (!provider.exists()) {
            return null;
        }
        return provider.getProject() instanceof TreProject tre ? tre.members().stream().map(TreProject.Member::email).toList() : null;
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
                    : usage("grant takes a <project>, a <catalog>, and " + MEMBERS + " <file> if the project has no " + ProjectMembers.FILE);
                case "add-member" -> positional.size() == 2 && options.isEmpty() ? addMember(positional.get(0), positional.get(1))
                                                                                       : usage("add-member takes a <project> and a <user id>");
                case "remove-member" -> positional.size() == 2 && options.isEmpty() ? removeMember(positional.get(0), positional.get(1))
                                                                                          : usage("remove-member takes a <project> and a <user id>");
                case "revoke" -> positional.size() == 2 && options.isEmpty() ? revoke(positional.get(0), positional.get(1))
                                                                               : usage("revoke takes a <project> and a <catalog>");
                case "release" -> positional.size() == 2 && !options.containsKey(MEMBERS) ? release(positional.get(0), positional.get(1), namespace)
                                                                                                : usage("release takes a <catalog> and a <table>");
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

    private int grant(String project, String catalog, String namespace, Path file, boolean ofTheProject) throws Exception {
        List<String> userIds = members(file);
        if (ofTheProject) {
            checkAgainstTheProjectFile(project, file);
        }
        PolarisProjectGrants.Members changed = grants.get().grant(project, catalog, namespace, userIds);
        out.printf("Project %s may read %s.%s, read-only, and has %d member(s)%n", project, catalog, namespace, userIds.size());
        changed.added().forEach(principal -> out.printf("Added %s%n", principal));
        changed.removed().forEach(principal -> out.printf("Removed %s%n", principal));
        return 0;
    }

    private int addMember(String project, String userId) throws Exception {
        grants.get().addMember(project, userId);
        out.printf("Added %s to project %s%n", PolarisProjectGrants.memberPrincipal(userId), project);
        return 0;
    }

    private int removeMember(String project, String userId) throws Exception {
        grants.get().removeMember(project, userId);
        out.printf("Removed %s from project %s%n", PolarisProjectGrants.memberPrincipal(userId), project);
        return 0;
    }

    private int revoke(String project, String catalog) throws Exception {
        List<String> removed = grants.get().revoke(project, catalog);
        out.printf("Project %s may no longer read %s; %d member(s) lost its role%n", project, catalog, removed.size());
        return 0;
    }

    private int release(String catalog, String table, String namespace) throws Exception {
        grants.get().release(catalog, table, namespace);
        out.printf("Moved %s of %s from %s to %s%n", table, catalog, PolarisProjectGrants.STAGING, namespace);
        return 0;
    }

    /**
     * The members file of the project is kept as its group is, and the group is made from the members that the project's file lists, which is the authority:
     * a grant from a file that differs from it would give access to someone the project does not list, or leave out someone it does, so it is refused.
     *
     * @throws IllegalStateException if they differ
     */
    private void checkAgainstTheProjectFile(String project, Path file) throws IOException {
        List<String> listed = listedMembers.apply(project);
        if (listed == null) {
            return;
        }
        ProjectMembers.Drift drift = ProjectMembers.drift(ProjectMembers.read(file), listed);
        if (!drift.none()) {
            throw new IllegalStateException(String.format(
                "The members file %s is not the members of project %s, whose file lists them:%s%s%nMake it so, in the project's home: manage-project run %s manage-identity sync-members "
                + "(the file lists the members, and the group and the members file are made from it)",
                file, project,
                drift.missing().isEmpty() ? "" : String.format("%n  not in the members file: %s", String.join(", ", drift.missing())),
                drift.extra().isEmpty() ? "" : String.format("%n  in the members file, not listed: %s", String.join(", ", drift.extra())),
                project));
        }
    }

    /**
     * @return the user ids that the file lists, one for each line, alone or before a comma and the email, without a blank line, a comment (a line that starts
     * with {@code #}) or a repeat
     * @throws IOException if the file cannot be read, or lists none: a project of no member is granted by {@code revoke}, not by an empty file
     */
    static List<String> members(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("No file " + file + " of the members: one user id for each line, or <user id>,<email>; for a project that has been made in the "
                                  + "identity, manage-identity writes it as it adds the members (sync-members, or up, which runs it)");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.strip();
            // a line is the user id alone, or the user id and the email, as the members file of the project has it
            String id = (text.contains(",") ? text.substring(0, text.indexOf(',')) : text).strip();
            if (!id.isEmpty() && !text.startsWith("#")) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new IOException("The file " + file + " lists no member: to end a project's access, use revoke");
        }
        return List.copyOf(ids);
    }

    /**
     * Whether Polaris is reached through a port-forward to its pod, as {@code POLARIS_ACCESS} says, the default, or through its public domain name,
     * {@code https}: the ingress of a Data Provider admits only the addresses that it is configured with, which need not include this machine.
     *
     * @throws IllegalArgumentException if it is neither
     */
    static boolean viaPortForward(String access) {
        if (access == null || access.isBlank() || access.equalsIgnoreCase("port-forward")) {
            return true;
        }
        if (access.equalsIgnoreCase("https")) {
            return false;
        }
        throw new IllegalArgumentException(String.format("%s should be 'https' or 'port-forward', not '%s'", POLARIS_ACCESS, access));
    }

    /**
     * @param open what is opened to reach Polaris, which the caller closes when the command is done
     */
    private static PolarisProjectGrants ofTheProviderInThisHome(List<AutoCloseable> open) throws Exception {
        String name = ConfigUtils.afonCloud();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("No cloud: run it in the home of a Data Provider's project, manage-project run <provider> manage-project-grants ..., "
                                            + "or give --cloud to afon-cli.sh");
        }
        AfonCloud cloud = AfonCloud.valueOf(name.trim().toUpperCase(Locale.ROOT));
        // as seen by the cloud whose Polaris it calls, at its endpoint
        AfonPropertyProvider properties = new OpsPropertyProvider().forCloud(cloud);
        String given = System.getProperty(POLARIS_ENDPOINT.getName());
        if (given == null && viaPortForward(properties.getValue(POLARIS_ACCESS, "port-forward"))) {
            KubeClient client = new KubeClient(properties);
            open.add(client);
            client.requireContextOf(cloud);
            String pod = client.runningPodWithLabel(StepOperations.POLARIS_POD_LABEL).orElseThrow(() -> new IllegalStateException(
                "No running pod with the label " + StepOperations.POLARIS_POD_LABEL + " in the kubectl context '" + client.getContext() + "'"));
            PortForwarder forward = new PortForwarder(client, "default", pod, 0, StepOperations.POLARIS_PORT);
            open.add(forward);
            given = "localhost:" + forward.localPort();
        }
        properties.setValue(POLARIS_ENDPOINT, given != null ? given : new PolarisEndpoint(cloud, properties).getEndpoint());
        return new PolarisProjectGrants(properties);
    }

    public static void main(String[] args) {
        List<AutoCloseable> open = new ArrayList<>();
        int status;
        try {
            status = new ManageProjectGrantsMain(() -> {
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
