package health.emrys.afon.cdk;

/**
 * Where a load balancer probes a backend's pods to decide whether they are healthy.  Without one the AWS ALB GETs {@code /} on
 * the traffic port and only accepts a 200, which none of our backends return: Keycloak and oauth2-proxy redirect, and Polaris
 * only serves its health on its management port.
 *
 * @param path the health endpoint's path
 * @param port {@link #TRAFFIC_PORT}, or the name of a Service port, which the AWS load balancer controller resolves to its node
 *             port (instance targets) or target port (ip targets), so that port must be exposed by the Service
 */
public record HealthCheck(String path, String port) {

    public static final String TRAFFIC_PORT = "traffic-port";

    public static HealthCheck onTrafficPort(String path) {
        return new HealthCheck(path, TRAFFIC_PORT);
    }
}
