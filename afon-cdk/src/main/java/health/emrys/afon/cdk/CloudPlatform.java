package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import health.emrys.afon.config.AfonCloud;

import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * What the components of a stack, eg Polaris or Jupyter, need of the cloud they are deployed to, so that a component
 * is written once and works on any cloud, taking its config from the cloud's own properties, see
 * {@link health.emrys.afon.config.AfonPropertyProvider#getCloudValue(AfonCloud, AfonCloudProperty)}.
 * <p>
 * On top of the {@link CloudProvider}, which is the cluster, a platform provides what is done differently per cloud
 * to run on it, eg exposing a service over HTTPS.
 */
public interface CloudPlatform extends CloudProvider {

    AfonCloud cloud();

    /**
     * @return the name of the cluster, which also names what is kept per cluster, eg the Loki tenant of its logs
     */
    String clusterName();

    /**
     * @return where Loki keeps the logs of the cluster
     * @throws UnsupportedOperationException if the platform has no log storage configured, so cannot run the monitoring
     */
    default LogStorage logStorage() {
        throw new UnsupportedOperationException("No log storage is configured for " + cloud());
    }

    /**
     * @return the Helm provider of the cluster
     */
    HelmProvider configureHelm();

    /**
     * @return whether the platform's load balancer routes to node ports, eg the AWS ALB with instance targets, so that
     * what is exposed over HTTPS needs a NodePort service rather than a ClusterIP one
     */
    default boolean routesToNodePorts() {
        return false;
    }

    /**
     * @return the Cilium labels, namespace included, selecting the pods of the ingress controller, if it runs in the cluster, else
     * none, as on AWS, whose ALB is outside of it.  A pod calling what the ingress exposes, eg the Spark driver calling Polaris
     * by its domain name, reaches the controller's pod, not an address outside of the cluster, as Cilium translates the address
     * of the load balancer, which is that of a service of the cluster: the egress policy of the pod must allow it.
     */
    default Map<String, String> ingressPodLabels() {
        return Map.of();
    }

    /**
     * @return what the Spark operator must wait for, by default the cluster's dependables
     */
    default List<? extends ITerraformDependable> sparkOperatorDependencies() {
        return getDependables();
    }

    /**
     * @return what a component with persistent volumes must wait for, so that its PVCs can be bound: by default the
     * cluster's dependables, as most clusters come with a default StorageClass and its provisioner
     */
    default List<? extends ITerraformDependable> storageDependencies() {
        return getDependables();
    }

    /**
     * A volume that outlives the cluster: it is kept when the stack is destroyed, and a cluster of the same name binds
     * the same volume again, so that what a workload keeps on it, eg Gitea's repositories, survives the cluster.
     *
     * @param name      what the volume is for, eg {@code gitea}, which names it apart from the cluster's other volumes
     * @param size      its size, as Kubernetes quantities of GiB, eg {@code 10Gi}
     * @param namespace the namespace of the claim
     * @return the claim on the volume, or empty if the platform has no such volumes, as by default, when the workload
     * falls back to a volume provisioned with the cluster, which goes with it
     */
    default Optional<RetainedVolumeClaim> retainedVolumeClaim(String name, String size, String namespace) {
        return Optional.empty();
    }

    /**
     * Exposes the Spark master outside the cluster, for the platforms that do.  By default it is not exposed.
     *
     * @param dependsOn the cluster's dependables, including that the Spark operator is ready
     */
    default void exposeSparkMaster(List<ITerraformDependable> dependsOn) {
    }

    /**
     * Makes the component reachable over HTTPS at its domain name, ie creates the ingress, and the certificate and DNS
     * record it needs.
     */
    HttpsExposure exposeHttps(HttpsRoute route);

}
