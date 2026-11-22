package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.POLARIS_ACCESS;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

/**
 * How this machine reaches the Polaris of a project it runs a command for, in one place for every command that calls it, so that none differs:
 * through a port-forward to its pod, which is the default, so that this machine need not be among the addresses that its ingress admits, or at its
 * public domain name, as {@code POLARIS_ACCESS} ({@code port-forward} or {@code https}) says.  An endpoint that the caller gives already, in the system
 * property {@code POLARIS_ENDPOINT}, eg of a port-forward it has made, is used as it is.
 *
 * <p>Closing it ends the port-forward and the Kubernetes client that it holds, if any.
 */
public final class PolarisAccess implements AutoCloseable {

    private final List<AutoCloseable> open;
    private final String endpoint;

    private PolarisAccess(List<AutoCloseable> open, String endpoint) {
        this.open = open;
        this.endpoint = endpoint;
    }

    /**
     * @param access the value of {@code POLARIS_ACCESS}
     * @return whether Polaris is reached through a port-forward to its pod, which is so when it says nothing
     * @throws IllegalArgumentException if it is neither {@code port-forward} nor {@code https}
     */
    public static boolean viaPortForward(String access) {
        if (access == null || access.isBlank() || access.equalsIgnoreCase("port-forward")) {
            return true;
        }
        if (access.equalsIgnoreCase("https")) {
            return false;
        }
        throw new IllegalArgumentException(String.format("%s should be 'https' or 'port-forward', not '%s'", POLARIS_ACCESS, access));
    }

    /**
     * @param properties as seen by the cloud, whose Polaris it is
     * @param cloud      the cloud of the project's Polaris, whose kubectl context must be the current one
     * @return the access, whose {@link #endpoint()} is {@code null} if Polaris is to be called at its public domain name
     */
    public static PolarisAccess open(AfonPropertyProvider properties, AfonCloud cloud) throws Exception {
        String given = System.getProperty(POLARIS_ENDPOINT.getName());
        if (given != null) {
            return new PolarisAccess(List.of(), given);
        }
        if (!viaPortForward(properties.getValue(POLARIS_ACCESS, "port-forward"))) {
            return new PolarisAccess(List.of(), null);
        }
        List<AutoCloseable> open = new ArrayList<>();
        try {
            KubeClient client = new KubeClient(properties);
            open.add(client);
            client.requireContextOf(cloud);
            String pod = client.runningPodWithLabel(StepOperations.POLARIS_POD_LABEL).orElseThrow(() -> new IllegalStateException(
                "No running pod with the label " + StepOperations.POLARIS_POD_LABEL + " in the kubectl context '" + client.getContext() + "'"));
            PortForwarder forward = new PortForwarder(client, "default", pod, 0, StepOperations.POLARIS_PORT);
            open.add(forward);
            return new PolarisAccess(open, "localhost:" + forward.localPort());
        } catch (Exception e) {
            closeAll(open);
            throw e;
        }
    }

    /**
     * @return where to call Polaris, {@code host:port}, or {@code null} for its public domain name
     */
    public String endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        closeAll(open);
    }

    private static void closeAll(List<AutoCloseable> open) {
        for (int i = open.size() - 1; i >= 0; i--) {
            try {
                open.get(i).close();
            } catch (Exception ignored) {
                // closing what was opened, which the command has done with, never fails it
            }
        }
    }
}
