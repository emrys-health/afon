package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonPropertyProvider;

import java.util.regex.Pattern;

import static health.emrys.afon.config.AfonProperty.SPARK_CLUSTER_NAME;

/**
 * The names of the Spark cluster and of what the Spark operator derives from it, {@code <cluster>-master} and
 * {@code <cluster>-worker} being the StatefulSets of the master and the workers.
 *
 * <p>The cluster name is {@code SPARK_CLUSTER_NAME}, which is {@value #DEFAULT_CLUSTER_NAME} when unset.  Changing it replaces the
 * Spark cluster of a deployed stack, see {@link SparkWorkloads}.
 */
public record SparkNames(String clusterName) {

    /** The name when {@code SPARK_CLUSTER_NAME} is blank or unset. */
    static final String DEFAULT_CLUSTER_NAME = "spark";
    /**
     * The name of every stack deployed before the name was configurable, which has no record of it: its Spark cluster is deleted
     * by this name.
     */
    static final String LEGACY_CLUSTER_NAME = "emrys";
    /** A Kubernetes DNS label, as the pod and service names derived from it must be, with room for {@code -master-0}. */
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z]([-a-z0-9]{0,40}[a-z0-9])?");

    public SparkNames {
        if (!DNS_LABEL.matcher(clusterName).matches()) {
            throw new IllegalArgumentException(
                "%s must be a lowercase DNS label of at most 42 characters, but is '%s'".formatted(SPARK_CLUSTER_NAME, clusterName));
        }
    }

    public static SparkNames from(AfonPropertyProvider properties) {
        String name = properties.getValue(SPARK_CLUSTER_NAME, "").trim();
        return new SparkNames(name.isEmpty() ? DEFAULT_CLUSTER_NAME : name);
    }

    public String master() {
        return clusterName + "-master";
    }

    public String worker() {
        return clusterName + "-worker";
    }

    public String masterPod() {
        return master() + "-0";
    }

    /** The service that the driver, and so the Spark master URL, reaches the master by. */
    public String masterService() {
        return master() + "-svc";
    }
}
