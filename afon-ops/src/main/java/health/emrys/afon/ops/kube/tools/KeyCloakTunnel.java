package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_PORT;

/**
 * Reaches the admin side of KeyCloak, ie its admin REST API and the {@code master} realm, through a port-forward to its pod, as its
 * ingress may only admit {@code <CLOUD>_KEYCLOAK_ALLOWED_CIDRS} to it, which need not include this machine.
 *
 * <p>The pod is looked for in the cluster of the current kubectl context, which must be that of the cloud. If none runs there, as
 * when the stacks use the KeyCloak of another stack, eg with {@code <CLOUD>_KEYCLOAK_EXTERNAL=true}, KeyCloak is reached through
 * its public domain name instead, so this machine must then be admitted to it.
 *
 * <p>KeyCloak takes the requests through the tunnel as local ones, so it answers them over plain http, as the {@code master} realm
 * only requires https of external requests, and issues the tokens for its public domain name, {@code KC_HOSTNAME}, as it does for
 * those of its ingress.
 */
public final class KeyCloakTunnel {

    private static final Logger logger = LoggerFactory.getLogger(KeyCloakTunnel.class);

    /** The label of the KeyCloak pod, as {@code KeyCloakConfig} deploys it. */
    public static final String POD_LABEL = "app=keycloak";

    @FunctionalInterface
    public interface Call {
        /**
         * @param endpoint where KeyCloak is reached, ie {@code localhost:<port>} of the port-forward, or {@code null} for its public
         *                 domain name
         */
        void run(String endpoint) throws Exception;
    }

    private KeyCloakTunnel() {
    }

    /**
     * Runs the call with the endpoint of a port-forward to the KeyCloak pod in the cluster of the cloud, closed when it returns, or
     * with {@code null} if no KeyCloak pod runs in that cluster.
     *
     * @throws IllegalStateException if the current kubectl context is not the cluster of the cloud
     */
    public static void run(AfonCloud cloud, AfonPropertyProvider properties, Call call) throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            client.requireContextOf(cloud);
            Optional<String> pod = client.runningPodWithLabel(POD_LABEL);
            if (pod.isEmpty()) {
                logger.warn("No running pod with the label {} in the kubectl context '{}', as when KeyCloak is another stack's: "
                            + "calling it through its public domain name, which must admit this machine", POD_LABEL,
                            client.getContext());
                call.run(null);
                return;
            }
            try (PortForwarder forward = new PortForwarder(client, "default", pod.get(), 0, KEYCLOAK_PORT)) {
                call.run("localhost:" + forward.localPort());
            }
        }
    }
}
