package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import health.emrys.afon.config.AfonCloud;

import java.util.List;

/**
 * What the components of a stack, eg Polaris or Jupyter, need of the cloud they are deployed to, so that a component
 * is written once and works on any cloud, taking its config from the cloud's own properties, see
 * {@link health.emrys.afon.config.AfonPropertyProvider#getCloudValue(AfonCloud, String)}.
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
     * @return what the Spark operator must wait for, by default the cluster's dependables
     */
    default List<? extends ITerraformDependable> sparkOperatorDependencies() {
        return getDependables();
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
