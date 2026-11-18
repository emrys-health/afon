package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.polaris.core.admin.model.NamespacePrivilege;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static health.emrys.afon.rest.HttpResponses.isFailure;
import static org.apache.polaris.core.admin.model.NamespacePrivilege.*;

/**
 * What a Data Provider grants a project, in its own Polaris and with its own credentials, so that the project's members read the catalog: the
 * Data Provider decides who, as well as what.  A project is let into a catalog by:
 * <ul>
 *   <li>a principal role for the project, {@link #projectPrincipalRole}, which only the project's members have;</li>
 *   <li>a catalog role for the project in the catalog, {@link #projectCatalogRole}, which has read-only access to a namespace, {@code released} unless
 *       the Data Provider says another, and nothing else: not {@code staging}, where the tables are made and checked, and not the other projects';</li>
 *   <li>a principal for each member that the Data Provider's contract names, {@link #memberPrincipal}, by the immutable id that the central identity
 *       gave them, which has the project's principal role.</li>
 * </ul>
 * Every call is made again without harm, and what is not named is taken away: a member no longer listed loses the project's role, and a principal
 * that has no role left is deleted, as it is the Data Provider's record of who may read, not of who exists.  The names of the roles and of the principals are
 * the Data Provider's allowlist of projects and of members: a token whose project claim or user claim names none that exists, reads nothing.
 */
public class PolarisProjectGrants {

    private static final Logger logger = LoggerFactory.getLogger(PolarisProjectGrants.class);

    /** The namespace where the Data Provider makes and checks a table, which no project reads. */
    public static final String STAGING = "staging";

    /** The namespace of the tables that are checked, which a project reads, unless the Data Provider gives another. */
    public static final String RELEASED = "released";

    /**
     * What a project may do in its namespace: list and read what is there, and nothing that changes it.  Granted on a namespace, it holds for the
     * tables and views in it, which the Data Provider adds later.
     */
    public static final Set<NamespacePrivilege> READ_ONLY = Set.of(NAMESPACE_LIST, NAMESPACE_READ_PROPERTIES, TABLE_LIST, TABLE_READ_PROPERTIES,
                                                                   TABLE_READ_DATA, VIEW_LIST, VIEW_READ_PROPERTIES);

    /** The prefix of the principal of a member, which names whose identity they are from, and keeps a name of the Data Provider's own from being one. */
    public static final String PRINCIPAL_PREFIX = "afon:";

    /** The names that Polaris and its administrators use, which no project's role or member's principal is. */
    static final Set<String> RESERVED = Set.of("root", "service_admin", "catalog_admin", "polaris_service_admin", "PRINCIPAL_ROLE:ALL");

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{7,62}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}");

    /** What changed for the members of a project. */
    public record Members(List<String> added, List<String> removed) {
    }

    private final CatalogConfigurer configurer;

    public PolarisProjectGrants(AfonPropertyProvider properties) {
        this(new CatalogConfigurer(PolarisRestClientFactory.clientFactory().createClient(properties)));
    }

    public PolarisProjectGrants(CatalogConfigurer configurer) {
        this.configurer = configurer;
    }

    /** @return the principal role of the project, {@code <project>Project}, by a fixed suffix, so that no project claim names a role that exists already */
    public static String projectPrincipalRole(String project) {
        requireName("project", project);
        String role = project + "Project";
        if (RESERVED.contains(role)) {
            throw new IllegalArgumentException("'" + role + "' is a name that Polaris keeps for itself");
        }
        return role;
    }

    /** @return the catalog role of the project, {@code <project>Reader} */
    public static String projectCatalogRole(String project) {
        requireName("project", project);
        return project + "Reader";
    }

    /** @return the principal of the member, {@code afon:<user id>} */
    public static String memberPrincipal(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("'" + userId + "' is not the id of a user of the central identity: letters, digits and hyphens, at least 8");
        }
        return PRINCIPAL_PREFIX + userId;
    }

    private static void requireName(String what, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("The " + what + " '" + name + "' is not a name: letters, digits, '.', '_' and '-', the first a letter or a digit");
        }
    }

    /**
     * Lets the project read the namespace of the catalog, and makes its members exactly those given: those listed that have not the project's role
     * get it, with a principal made for them if they have none, and those that have it and are not listed lose it.  The namespaces are made if they are
     * absent, so that a table can be registered in {@value #STAGING} before anything is released.
     *
     * @param namespace the namespace that the project reads, {@value #RELEASED} if the Data Provider gives none
     * @param userIds   the ids of the members that the Data Provider's contract names
     */
    public Members grant(String project, String catalog, String namespace, List<String> userIds) throws IOException, IncorrectConfigException {
        String role = projectPrincipalRole(project);
        String catalogRole = projectCatalogRole(project);
        requireName("catalog", catalog);
        requireName("namespace", namespace);
        if (STAGING.equals(namespace)) {
            throw new IllegalArgumentException("The project reads the tables that are released, so the namespace is not " + STAGING);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String userId : userIds) {
            wanted.add(memberPrincipal(userId));
        }
        ok(configurer.createNamespaceIfAbsent(catalog, STAGING), "make the namespace " + STAGING);
        ok(configurer.createNamespaceIfAbsent(catalog, namespace), "make the namespace " + namespace);
        ok(configurer.createPrincipalRole(role), "make the principal role " + role);
        ok(configurer.createCatalogRole(catalog, catalogRole), "make the catalog role " + catalogRole);
        for (NamespacePrivilege privilege : READ_ONLY) {
            ok(configurer.createNamespaceGrant(catalog, catalogRole, namespace, privilege), "grant " + privilege + " on " + namespace);
        }
        ok(configurer.associatePrincipalRole(catalog, role, catalogRole), "give the principal role " + role + " the catalog role " + catalogRole);

        List<String> current = principalsWith(role);
        List<String> added = new ArrayList<>();
        for (String principal : wanted) {
            if (!current.contains(principal)) {
                ok(configurer.createPrincipal(principal), "make the principal " + principal);
                ok(configurer.associatePrincipalRole(principal, role), "give " + principal + " the role " + role);
                added.add(principal);
            }
        }
        List<String> removed = new ArrayList<>();
        for (String principal : current) {
            if (principal.startsWith(PRINCIPAL_PREFIX) && !wanted.contains(principal)) {
                takeAway(principal, role);
                removed.add(principal);
            }
        }
        logger.info("Project {} may read {}.{}: {} member(s) added, {} removed", project, catalog, namespace, added.size(), removed.size());
        return new Members(added, removed);
    }

    /**
     * Lets one more member read what the project reads: the project must have been granted the catalog.
     */
    public void addMember(String project, String userId) throws IOException, IncorrectConfigException {
        String role = projectPrincipalRole(project);
        String principal = memberPrincipal(userId);
        if (!principalRoleExists(role)) {
            throw new IllegalStateException("The project " + project + " has not been granted a catalog: there is no principal role " + role + ", so grant it first");
        }
        ok(configurer.createPrincipal(principal), "make the principal " + principal);
        ok(configurer.associatePrincipalRole(principal, role), "give " + principal + " the role " + role);
    }

    /**
     * Takes the project's role from the member, and deletes their principal if that was the last role they had.
     */
    public void removeMember(String project, String userId) throws IOException {
        takeAway(memberPrincipal(userId), projectPrincipalRole(project));
    }

    /**
     * Ends the project's access to the catalog: its members lose the project's role, and a principal that has none left is deleted; then the
     * project's principal role and its catalog role, with their grants, are.
     */
    public List<String> revoke(String project, String catalog) throws IOException {
        String role = projectPrincipalRole(project);
        requireName("catalog", catalog);
        List<String> removed = new ArrayList<>();
        for (String principal : principalsWith(role)) {
            if (principal.startsWith(PRINCIPAL_PREFIX)) {
                takeAway(principal, role);
                removed.add(principal);
            }
        }
        configurer.deletePrincipalRole(role);
        configurer.deleteCatalogRole(catalog, projectCatalogRole(project));
        logger.info("Project {} may no longer read {}: {} member(s) lost its role", project, catalog, removed.size());
        return removed;
    }

    /**
     * Moves a table that is checked from {@value #STAGING} to the namespace that the projects read.
     */
    public void release(String catalog, String table, String namespace) throws IOException, IncorrectConfigException {
        requireName("catalog", catalog);
        requireName("table", table);
        requireName("namespace", namespace);
        ok(configurer.renameTable(catalog, STAGING, namespace, table), "move " + table + " from " + STAGING + " to " + namespace);
    }

    private List<String> principalsWith(String role) throws IOException {
        try {
            return configurer.principalsOf(role);
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private boolean principalRoleExists(String role) throws IOException {
        return configurer.principalRoleExists(role);
    }

    private void takeAway(String principal, String role) throws IOException {
        configurer.removePrincipalRole(principal, role);
        try {
            if (configurer.principalRolesOf(principal).isEmpty()) {
                configurer.deletePrincipal(principal);
            }
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private static void ok(ClassicHttpResponse response, String what) throws IOException {
        if (isFailure(response)) {
            throw new IOException("Polaris could not " + what + ": HTTP " + response.getCode() + reason(response));
        }
    }

    /** @return what Polaris said of the refusal, in its own words, which is what says what is wrong with the request, or nothing */
    private static String reason(ClassicHttpResponse response) {
        try {
            String body = response.getEntity() == null ? "" : org.apache.hc.core5.http.io.entity.EntityUtils.toString(response.getEntity()).strip();
            return body.isEmpty() ? "" : " " + (body.length() > 400 ? body.substring(0, 400) + "..." : body);
        } catch (IOException | ParseException | RuntimeException e) {
            return "";
        }
    }
}
