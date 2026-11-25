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
 * The names that a project stack's resources have, so that no two project stacks share one, even in one cloud account, and the same project stack id in
 * two accounts does not meet itself.  A name that is unique only in its account or resource group, the cluster's, is the project stack id
 * and what says what it is.  A name that is unique in the whole cloud, an Azure storage account or key vault, an S3 or GCS bucket, is
 * {@code afon-<token>}, or {@code afonstorage<token>} for a storage account, where the token is a hash of the cloud, the account and the project stack id, so that it is stable, which what outlives
 * the stacks needs, and fits every cloud's limits, whatever the id.  {@link #find} says which project stack a name is the token of.
 */
public final class ProjectStackNames {

    /** A {@code jdbc:postgresql://host[:port]/database[?parameters]} URL, by its part up to the database, the database and the parameters. */
    private static final Pattern DB_URL = Pattern.compile("^(jdbc:postgresql://[^/?]+/)([^?/]+)(\\?.*)?$");

    /** The number of hex characters of the token. */
    static final int TOKEN_LENGTH = 10;

    /** The properties that name the cloud account, by cloud, in the config the project stack is made from. */
    private static final Map<AfonCloud, String> ACCOUNT_PROPERTIES = Map.of(AfonCloud.AZURE, "AZURE_SUBSCRIPTION",
                                                                           AfonCloud.AWS, "AWS_ACCOUNT_ID",
                                                                           AfonCloud.GCP, "GCP_PROJECT_ID");

    private ProjectStackNames() {
    }

    /** The longest name of a cluster, by cloud: AKS 63, EKS 100, GKE 40. */
    private static final Map<AfonCloud, Integer> CLUSTER_NAME_LIMITS = Map.of(AfonCloud.AZURE, 63, AfonCloud.AWS, 100, AfonCloud.GCP, 40);

    /**
     * @return the name of the cluster of the stack type, {@code <stack type>-<project-stack id>-cluster}, as the cloud provider names it from
     * {@code CLUSTER_NAME}
     */
    static String clusterName(String stackType, String projectStackId) {
        return stackType + "-" + projectStackId + "-cluster";
    }

    /**
     * @return the problems with the names of the clusters of the project stack's stacks, ie the ones over the limit of the cloud: the id of the
     * project stack is part of them
     */
    static List<String> clusterNameProblems(ProjectStack projectStack) {
        List<String> problems = new ArrayList<>();
        int limit = CLUSTER_NAME_LIMITS.get(projectStack.cloud());
        for (ProjectStackPlan.Stage stage : ProjectStackPlan.of(projectStack).stages()) {
            // the create and register stages make no cluster
            if (stage.stack() == null || stage.id() == ProjectStackPlan.StageId.CREATE || stage.id() == ProjectStackPlan.StageId.REGISTER) {
                continue;
            }
            String name = clusterName(stage.stack().type(), projectStack.id().value());
            if (name.length() > limit) {
                problems.add(String.format("project-stack.id '%s' is too long for %s: the cluster of the stack %s would be named %s, %d characters, over the limit of %d",
                                           projectStack.id(), projectStack.cloud().toString().toLowerCase(Locale.ROOT), stage.stack(), name, name.length(), limit));
            }
        }
        return problems;
    }

    /**
     * @return the problems with the project stack's domains that another project stack of the same cloud has too: two clusters cannot be served at one
     */
    public static List<String> domainCollisions(ProjectStack projectStack, List<ProjectStack> others) {
        List<String> problems = new ArrayList<>();
        Map<String, String> mine = domains(projectStack);
        for (ProjectStack other : others) {
            if (other.id().equals(projectStack.id()) || other.cloud() != projectStack.cloud()) {
                continue;
            }
            Map<String, String> theirs = domains(other);
            mine.forEach((name, domain) -> theirs.forEach((otherName, otherDomain) -> {
                if (domain.equalsIgnoreCase(otherDomain)) {
                    problems.add(String.format("the domain %s of %s is also that of %s (%s): one domain serves one project stack", domain, name, other.id(), otherName));
                }
            }));
        }
        return problems;
    }

    private static Map<String, String> domains(ProjectStack projectStack) {
        Map<String, String> domains = new LinkedHashMap<>();
        if (projectStack instanceof TreProjectStack tre) {
            domains.putAll(tre.tre().domains());
            if (tre.git() != null) {
                domains.put("stacks.git.domain", tre.git().domain());
            }
        } else if (projectStack instanceof IdentityProjectStack identity && identity.domain() != null) {
            domains.put("stacks.identity.domain", identity.domain());
        } else if (projectStack instanceof DataProjectStack data && data.domain() != null) {
            domains.put("stacks.data.domain", data.domain());
        }
        return domains;
    }

    /**
     * @return the account of the cloud in the config the project stack is made from: the subscription of Azure, the account id of AWS or the
     * project stack id of GCP
     * @throws ProjectStackException if the config names none
     */
    static String account(AfonCloud cloud, Properties env, String envName) {
        String property = ACCOUNT_PROPERTIES.get(cloud);
        String account = env.getProperty(property, "").trim();
        if (account.isEmpty()) {
            throw new ProjectStackException(FileProjectStackProvider.FILE, List.of(String.format(
                "cannot name the project stack's resources: %s is not set in the config the project stack is made from%s, and the names of the resources "
                    + "that are unique in the whole of %s depend on the account", property, envName == null ? "" : " (config-" + envName + ")",
                cloud.toString().toLowerCase(Locale.ROOT))));
        }
        return account;
    }

    /**
     * @return the token of the project stack in the account: the first {@value #TOKEN_LENGTH} hex characters of the SHA-256 of
     * {@code <cloud>:<account>:<project-stack id>}, in lower case
     */
    public static String token(AfonCloud cloud, String account, String projectStackId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                                       .digest((cloud.toString().toLowerCase(Locale.ROOT) + ":" + account + ":" + projectStackId).getBytes(StandardCharsets.UTF_8));
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
     * @return the domain of the env with the project stack id as a label after the first, eg {@code azure-jupyter.emrys2.click} of project stack {@code p1} is
     * {@code azure-jupyter.p1.emrys2.click}: under the parent domain of the env, so that it is delegated from the zone that hosts it, as the env's is
     * @throws ProjectStackException if the domain has no parent domain, eg a registered domain, which the project stack's would be under the top level domain
     */
    static String subdomain(String property, String envDomain, String projectStackId) {
        return subdomain(property, envDomain, projectStackId, "stacks.tre.domains");
    }

    /**
     * @param where the key of the project stack's file that gives the domain instead, which a problem says to set
     * @see #subdomain(String, String, String)
     */
    static String subdomain(String property, String envDomain, String projectStackId, String where) {
        String[] labels = envDomain.split("\\.");
        if (labels.length < 3) {
            throw new ProjectStackException(FileProjectStackProvider.FILE, List.of(String.format(
                "cannot derive the project stack's %s from %s of the config the project stack is made from: it has no parent domain to put the project stack's under; "
                    + "set it in %s", property, envDomain, where)));
        }
        return labels[0] + "." + projectStackId + "." + String.join(".", java.util.Arrays.copyOfRange(labels, 1, labels.length));
    }

    /**
     * @param env the config the project stack is made from, with its references resolved
     * @return the properties that name the project stack's resources, in the order they are written, and the file of the config each is written to:
     * {@code afon.properties} for those of every cloud, {@code afon-<cloud>.properties}, and {@code catalogs-<cloud>.properties} for the
     * names of the catalogs' buckets
     * @throws ProjectStackException if the config does not name the account
     */
    static Map<String, Map<String, String>> derive(@NonNull ProjectStack projectStack, @NonNull Properties env, String envName) {
        AfonCloud cloud = projectStack.cloud();
        String prefix = cloud.name() + "_";
        String token = token(cloud, account(cloud, env, envName), projectStack.id().value());
        Map<String, String> common = new LinkedHashMap<>();
        Map<String, String> cloudSpecific = new LinkedHashMap<>();
        Map<String, String> catalogs = new LinkedHashMap<>();
        common.put("CLUSTER_NAME", projectStack.id().value() + "-cluster");
        if (projectStack instanceof TreProjectStack) {
            // the TRE's domains are under the project stack, in place of the env's own, which another TRE of the env would claim too
            for (String domain : List.of("POLARIS_DOMAIN_NAME", "JUPYTER_DOMAIN_NAME")) {
                String envDomain = env.getProperty(prefix + domain, "").trim();
                if (!envDomain.isEmpty()) {
                    cloudSpecific.put(prefix + domain, subdomain(prefix + domain, envDomain, projectStack.id().value()));
                }
            }
        }
        if (projectStack instanceof IdentityProjectStack) {
            // the identity's domain is under its project stack too, as a TRE's are: the env may serve a Keycloak of its own at the env's domain, which the
            // identity would claim as well.  What the project stack's file gives, in stacks.identity.domain, wins.
            String envDomain = env.getProperty(prefix + "OIDC_DOMAIN_NAME", "").trim();
            if (envDomain.isEmpty()) {
                envDomain = env.getProperty("OIDC_DOMAIN_NAME", "").trim();
            }
            // an env domain with no parent, eg emrysoauth.click, is left as it is: the project stack's is not under it, and the file may name one
            if (envDomain.split("\\.").length >= 3 && !envDomain.contains("[") && !envDomain.contains("$")) {
                cloudSpecific.put(prefix + "OIDC_DOMAIN_NAME", subdomain(prefix + "OIDC_DOMAIN_NAME", envDomain, projectStack.id().value(), "stacks.identity.domain"));
            }
        }
        if (projectStack instanceof DataProjectStack data && data.domain() == null) {
            // the Data Provider's Polaris is under its project stack, as a TRE's domains are: two Data Providers made from one env would otherwise serve one name, and
            // Polaris keeps its database by its domain, so each has a database of its own too.  What the file says, in stacks.data.domain, wins.
            String envDomain = env.getProperty(prefix + "POLARIS_DOMAIN_NAME", "").trim();
            if (!envDomain.isEmpty()) {
                cloudSpecific.put(prefix + "POLARIS_DOMAIN_NAME", subdomain(prefix + "POLARIS_DOMAIN_NAME", envDomain, projectStack.id().value(), "stacks.data.domain"));
            }
        }
        switch (cloud) {
            case AZURE -> {
                // the containers of its catalogs, and of Git and the logs, are in the storage account, so they are the project stack's own
                cloudSpecific.put("AZURE_STORAGE_ACCOUNT", "afonstorage" + token);
                cloudSpecific.put("AZURE_KEY_VAULT_NAME", "afon-kv-" + token);
            }
            case AWS, GCP -> {
                // buckets are named in the whole cloud
                if (projectStack instanceof TreProjectStack tre && tre.create().gitBucket() == null && !env.getProperty(prefix + "GITEA_BUCKET", "").isBlank()) {
                    cloudSpecific.put(prefix + "GITEA_BUCKET", "afon-" + token + "-gitea");
                }
                if (!env.getProperty(prefix + "POLARIS_LOG_BUCKET", "").isBlank()) {
                    cloudSpecific.put(prefix + "POLARIS_LOG_BUCKET", "afon-" + token + "-polaris-logs");
                }
                if (!(projectStack instanceof DataProjectStack)) {
                    for (String id : env.getProperty(prefix + "CATALOG_IDS", "").split(",")) {
                        if (!id.isBlank() && !env.getProperty(prefix + "BUCKET_NAME_" + id.trim(), "").isBlank()) {
                            catalogs.put(prefix + "BUCKET_NAME_" + id.trim(), "afon-" + token + "-" + id.trim().replace('_', '-').toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        if (projectStack instanceof DataProjectStack data) {
            // the catalogs of a Data Provider are its own, in place of those of the env: each is internal, named by its id, with storage that is the project stack's
            // (a container of its storage account on Azure, a bucket named in the whole cloud on AWS and GCP)
            catalogs.put(prefix + "CATALOG_IDS", String.join(",", data.catalogs()));
            for (String id : data.catalogs()) {
                String name = id.replace('_', '-');
                catalogs.put(prefix + "CATALOG_NAME_" + id, id);
                catalogs.put(prefix + "CATALOG_TYPE_" + id, "internal");
                catalogs.put(prefix + "BUCKET_NAME_" + id, cloud == AfonCloud.AZURE ? name : "afon-" + token + "-" + name);
            }
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        if (projectStack instanceof IdentityProjectStack) {
            // its Keycloak has a database of its own, as the identity of an env that has a Keycloak too would otherwise share the realm and the users of
            // that one: the stack makes the database if it is absent
            Matcher url = DB_URL.matcher(env.getProperty("KEYCLOAK_DB_URL", "").trim());
            if (url.matches()) {
                secrets.put("KEYCLOAK_DB_URL", url.group(1) + "keycloak-" + projectStack.id().value() + (url.group(3) == null ? "" : url.group(3)));
            }
        }
        Map<String, Map<String, String>> byFile = new LinkedHashMap<>();
        byFile.put(ProjectStackEnv.COMMON_FILE, common);
        byFile.put("afon-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", cloudSpecific);
        byFile.put("catalogs-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", catalogs);
        if (!secrets.isEmpty()) {
            byFile.put("secret.properties", secrets);
        }
        return byFile;
    }

    /**
     * @return the names of the project stack's resources that are in the whole cloud, to tell by a name which project stack it belongs to
     */
    public static List<String> globalNames(ProjectStack projectStack, Properties env) {
        List<String> names = new ArrayList<>();
        derive(projectStack, env, projectStack.env()).values().forEach(map -> map.forEach((name, value) -> {
            if (!name.equals("CLUSTER_NAME")) {
                names.add(value);
            }
        }));
        return names;
    }

    /**
     * @return the property whose value in the project stack is the name, among those of the names that are unique in the whole cloud, or empty
     */
    public static java.util.Optional<String> propertyOf(ProjectStack projectStack, Properties env, String name) {
        for (Map<String, String> names : derive(projectStack, env, projectStack.env()).values()) {
            for (Map.Entry<String, String> entry : names.entrySet()) {
                if (!entry.getKey().equals("CLUSTER_NAME") && entry.getValue().equalsIgnoreCase(name)) {
                    return java.util.Optional.of(entry.getKey());
                }
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * @return whether the name is one that the project stack has in the whole cloud
     */
    public static boolean find(ProjectStack projectStack, Properties env, String name) {
        return propertyOf(projectStack, env, name).isPresent();
    }

    /**
     * @return the names of the project stack's resources, by the property that names them, and the cluster of each of its stacks by the stack
     */
    public static Map<String, String> names(ProjectStack projectStack, Properties env) {
        Map<String, String> all = new LinkedHashMap<>();
        for (ProjectStackPlan.Stage stage : ProjectStackPlan.of(projectStack).stages()) {
            if (stage.stack() != null && stage.id() != ProjectStackPlan.StageId.CREATE && stage.id() != ProjectStackPlan.StageId.REGISTER) {
                all.put("cluster of " + stage.stack(), clusterName(stage.stack().type(), projectStack.id().value()));
            }
        }
        derive(projectStack, env, projectStack.env()).values().forEach(all::putAll);
        return all;
    }
}
