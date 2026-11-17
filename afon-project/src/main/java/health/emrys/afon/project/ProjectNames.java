package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names that a project's resources have, so that no two projects share one, even in one cloud account, and the same project id in
 * two accounts does not meet itself.  A name that is unique only in its account or resource group, the cluster's, is the project id
 * and what says what it is.  A name that is unique in the whole cloud, an Azure storage account or key vault, an S3 or GCS bucket, is
 * {@code afon-<token>}, or {@code afonstorage<token>} for a storage account, where the token is a hash of the cloud, the account and the project id, so that it is stable, which what outlives
 * the stacks needs, and fits every cloud's limits, whatever the id.  {@link #find} says which project a name is the token of.
 */
public final class ProjectNames {

    /** A {@code jdbc:postgresql://host[:port]/database[?parameters]} URL, by its part up to the database, the database and the parameters. */
    private static final Pattern DB_URL = Pattern.compile("^(jdbc:postgresql://[^/?]+/)([^?/]+)(\\?.*)?$");

    /** The number of hex characters of the token. */
    static final int TOKEN_LENGTH = 10;

    /** The properties that name the cloud account, by cloud, in the config the project is made from. */
    private static final Map<AfonCloud, String> ACCOUNT_PROPERTIES = Map.of(AfonCloud.AZURE, "AZURE_SUBSCRIPTION",
                                                                           AfonCloud.AWS, "AWS_ACCOUNT_ID",
                                                                           AfonCloud.GCP, "GCP_PROJECT_ID");

    private ProjectNames() {
    }

    /** The longest name of a cluster, by cloud: AKS 63, EKS 100, GKE 40. */
    private static final Map<AfonCloud, Integer> CLUSTER_NAME_LIMITS = Map.of(AfonCloud.AZURE, 63, AfonCloud.AWS, 100, AfonCloud.GCP, 40);

    /**
     * @return the name of the cluster of the stack type, {@code <stack type>-<project id>-cluster}, as the cloud provider names it from
     * {@code CLUSTER_NAME}
     */
    static String clusterName(String stackType, String projectId) {
        return stackType + "-" + projectId + "-cluster";
    }

    /**
     * @return the problems with the names of the clusters of the project's stacks, ie the ones over the limit of the cloud: the id of the
     * project is part of them
     */
    static List<String> clusterNameProblems(AfonProject project) {
        List<String> problems = new ArrayList<>();
        int limit = CLUSTER_NAME_LIMITS.get(project.cloud());
        for (ProjectPlan.Stage stage : ProjectPlan.of(project).stages()) {
            // the create and register stages make no cluster
            if (stage.stack() == null || stage.id() == ProjectPlan.StageId.CREATE || stage.id() == ProjectPlan.StageId.REGISTER) {
                continue;
            }
            String name = clusterName(stage.stack().type(), project.id().value());
            if (name.length() > limit) {
                problems.add(String.format("project.id '%s' is too long for %s: the cluster of the stack %s would be named %s, %d characters, over the limit of %d",
                                           project.id(), project.cloud().toString().toLowerCase(Locale.ROOT), stage.stack(), name, name.length(), limit));
            }
        }
        return problems;
    }

    /**
     * @return the problems with the project's domains that another project of the same cloud has too: two clusters cannot be served at one
     */
    public static List<String> domainCollisions(AfonProject project, List<AfonProject> others) {
        List<String> problems = new ArrayList<>();
        Map<String, String> mine = domains(project);
        for (AfonProject other : others) {
            if (other.id().equals(project.id()) || other.cloud() != project.cloud()) {
                continue;
            }
            Map<String, String> theirs = domains(other);
            mine.forEach((name, domain) -> theirs.forEach((otherName, otherDomain) -> {
                if (domain.equalsIgnoreCase(otherDomain)) {
                    problems.add(String.format("the domain %s of %s is also that of %s (%s): one domain serves one project", domain, name, other.id(), otherName));
                }
            }));
        }
        return problems;
    }

    private static Map<String, String> domains(AfonProject project) {
        Map<String, String> domains = new LinkedHashMap<>();
        if (project instanceof TreProject tre) {
            domains.putAll(tre.tre().domains());
            if (tre.git() != null) {
                domains.put("stacks.git.domain", tre.git().domain());
            }
        } else if (project instanceof IdentityProject identity && identity.domain() != null) {
            domains.put("stacks.identity.domain", identity.domain());
        }
        return domains;
    }

    /**
     * @return the account of the cloud in the config the project is made from: the subscription of Azure, the account id of AWS or the
     * project id of GCP
     * @throws ProjectException if the config names none
     */
    static String account(AfonCloud cloud, Properties env, String envName) {
        String property = ACCOUNT_PROPERTIES.get(cloud);
        String account = env.getProperty(property, "").trim();
        if (account.isEmpty()) {
            throw new ProjectException(FileProjectProvider.FILE, List.of(String.format(
                "cannot name the project's resources: %s is not set in the config the project is made from%s, and the names of the resources "
                    + "that are unique in the whole of %s depend on the account", property, envName == null ? "" : " (config-" + envName + ")",
                cloud.toString().toLowerCase(Locale.ROOT))));
        }
        return account;
    }

    /**
     * @return the token of the project in the account: the first {@value #TOKEN_LENGTH} hex characters of the SHA-256 of
     * {@code <cloud>:<account>:<project id>}, in lower case
     */
    public static String token(AfonCloud cloud, String account, String projectId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                                       .digest((cloud.toString().toLowerCase(Locale.ROOT) + ":" + account + ":" + projectId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, TOKEN_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-256", e);
        }
    }

    /**
     * @return the domain of the env with the project id as a label after the first, eg {@code azure-jupyter.emrys2.click} of project {@code p1} is
     * {@code azure-jupyter.p1.emrys2.click}: under the parent domain of the env, so that it is delegated from the zone that hosts it, as the env's is
     * @throws ProjectException if the domain has no parent domain, eg a registered domain, which the project's would be under the top level domain
     */
    static String subdomain(String property, String envDomain, String projectId) {
        return subdomain(property, envDomain, projectId, "stacks.tre.domains");
    }

    /**
     * @param where the key of the project's file that gives the domain instead, which a problem says to set
     * @see #subdomain(String, String, String)
     */
    static String subdomain(String property, String envDomain, String projectId, String where) {
        String[] labels = envDomain.split("\\.");
        if (labels.length < 3) {
            throw new ProjectException(FileProjectProvider.FILE, List.of(String.format(
                "cannot derive the project's %s from %s of the config the project is made from: it has no parent domain to put the project's under; "
                    + "set it in %s", property, envDomain, where)));
        }
        return labels[0] + "." + projectId + "." + String.join(".", java.util.Arrays.copyOfRange(labels, 1, labels.length));
    }

    /**
     * @param env the config the project is made from, with its references resolved
     * @return the properties that name the project's resources, in the order they are written, and the file of the config each is written to:
     * {@code afon.properties} for those of every cloud, {@code afon-<cloud>.properties}, and {@code catalogs-<cloud>.properties} for the
     * names of the catalogs' buckets
     * @throws ProjectException if the config does not name the account
     */
    static Map<String, Map<String, String>> derive(@NonNull AfonProject project, @NonNull Properties env, String envName) {
        AfonCloud cloud = project.cloud();
        String prefix = cloud.name() + "_";
        String token = token(cloud, account(cloud, env, envName), project.id().value());
        Map<String, String> common = new LinkedHashMap<>();
        Map<String, String> cloudSpecific = new LinkedHashMap<>();
        Map<String, String> catalogs = new LinkedHashMap<>();
        common.put("CLUSTER_NAME", project.id().value() + "-cluster");
        if (project instanceof TreProject) {
            // the TRE's domains are under the project, in place of the env's own, which another TRE of the env would claim too
            for (String domain : List.of("POLARIS_DOMAIN_NAME", "JUPYTER_DOMAIN_NAME")) {
                String envDomain = env.getProperty(prefix + domain, "").trim();
                if (!envDomain.isEmpty()) {
                    cloudSpecific.put(prefix + domain, subdomain(prefix + domain, envDomain, project.id().value()));
                }
            }
        }
        if (project instanceof IdentityProject) {
            // the identity's domain is under its project too, as a TRE's are: the env may serve a Keycloak of its own at the env's domain, which the
            // identity would claim as well.  What the project's file gives, in stacks.identity.domain, wins.
            String envDomain = env.getProperty(prefix + "OIDC_DOMAIN_NAME", "").trim();
            if (envDomain.isEmpty()) {
                envDomain = env.getProperty("OIDC_DOMAIN_NAME", "").trim();
            }
            // an env domain with no parent, eg emrysoauth.click, is left as it is: the project's is not under it, and the file may name one
            if (envDomain.split("\\.").length >= 3 && !envDomain.contains("[") && !envDomain.contains("$")) {
                cloudSpecific.put(prefix + "OIDC_DOMAIN_NAME", subdomain(prefix + "OIDC_DOMAIN_NAME", envDomain, project.id().value(), "stacks.identity.domain"));
            }
        }
        switch (cloud) {
            case AZURE -> {
                // the containers of its catalogs, and of Git and the logs, are in the storage account, so they are the project's own
                cloudSpecific.put("AZURE_STORAGE_ACCOUNT", "afonstorage" + token);
                cloudSpecific.put("AZURE_KEY_VAULT_NAME", "afon-kv-" + token);
            }
            case AWS, GCP -> {
                // buckets are named in the whole cloud
                if (project instanceof TreProject tre && tre.create().gitBucket() == null && !env.getProperty(prefix + "GITEA_BUCKET", "").isBlank()) {
                    cloudSpecific.put(prefix + "GITEA_BUCKET", "afon-" + token + "-gitea");
                }
                if (!env.getProperty(prefix + "POLARIS_LOG_BUCKET", "").isBlank()) {
                    cloudSpecific.put(prefix + "POLARIS_LOG_BUCKET", "afon-" + token + "-polaris-logs");
                }
                for (String id : env.getProperty(prefix + "CATALOG_IDS", "").split(",")) {
                    if (!id.isBlank() && !env.getProperty(prefix + "BUCKET_NAME_" + id.trim(), "").isBlank()) {
                        catalogs.put(prefix + "BUCKET_NAME_" + id.trim(), "afon-" + token + "-" + id.trim().replace('_', '-').toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        if (project instanceof IdentityProject) {
            // its Keycloak has a database of its own, as the identity of an env that has a Keycloak too would otherwise share the realm and the users of
            // that one: the stack makes the database if it is absent
            Matcher url = DB_URL.matcher(env.getProperty("KEYCLOAK_DB_URL", "").trim());
            if (url.matches()) {
                secrets.put("KEYCLOAK_DB_URL", url.group(1) + "keycloak-" + project.id().value() + (url.group(3) == null ? "" : url.group(3)));
            }
        }
        Map<String, Map<String, String>> byFile = new LinkedHashMap<>();
        byFile.put(ProjectEnv.COMMON_FILE, common);
        byFile.put("afon-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", cloudSpecific);
        byFile.put("catalogs-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", catalogs);
        if (!secrets.isEmpty()) {
            byFile.put("secret.properties", secrets);
        }
        return byFile;
    }

    /**
     * @return the names of the project's resources that are in the whole cloud, to tell by a name which project it belongs to
     */
    public static List<String> globalNames(AfonProject project, Properties env) {
        List<String> names = new ArrayList<>();
        derive(project, env, project.env()).values().forEach(map -> map.forEach((name, value) -> {
            if (!name.equals("CLUSTER_NAME")) {
                names.add(value);
            }
        }));
        return names;
    }

    /**
     * @return the property whose value in the project is the name, among those of the names that are unique in the whole cloud, or empty
     */
    public static java.util.Optional<String> propertyOf(AfonProject project, Properties env, String name) {
        for (Map<String, String> names : derive(project, env, project.env()).values()) {
            for (Map.Entry<String, String> entry : names.entrySet()) {
                if (!entry.getKey().equals("CLUSTER_NAME") && entry.getValue().equalsIgnoreCase(name)) {
                    return java.util.Optional.of(entry.getKey());
                }
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * @return whether the name is one that the project has in the whole cloud
     */
    public static boolean find(AfonProject project, Properties env, String name) {
        return propertyOf(project, env, name).isPresent();
    }

    /**
     * @return the names of the project's resources, by the property that names them, and the cluster of each of its stacks by the stack
     */
    public static Map<String, String> names(AfonProject project, Properties env) {
        Map<String, String> all = new LinkedHashMap<>();
        for (ProjectPlan.Stage stage : ProjectPlan.of(project).stages()) {
            if (stage.stack() != null && stage.id() != ProjectPlan.StageId.CREATE && stage.id() != ProjectPlan.StageId.REGISTER) {
                all.put("cluster of " + stage.stack(), clusterName(stage.stack().type(), project.id().value()));
            }
        }
        derive(project, env, project.env()).values().forEach(all::putAll);
        return all;
    }
}
