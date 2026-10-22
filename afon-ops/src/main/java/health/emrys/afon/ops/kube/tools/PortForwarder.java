package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.PortForward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static health.emrys.afon.ops.smoke.config.SmokeConfig.KUBERNETES_NAMESPACE;

/**
 * A port-forwarding session, like {@code kubectl port-forward}. Listens on localhost and tunnels each
 * accepted connection to a port on a pod until closed. Usage:
 * <pre>
 *   PortForwarder [-n namespace] &lt;pod&gt; &lt;localPort&gt;:&lt;remotePort&gt;
 * </pre>
 * Eg
 * <pre>
 *   PortForwarder spark-connector-0-driver 15002:15002
 * </pre>
 * Runs until killed. A local port of 0 picks a free port.
 */
public class PortForwarder implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PortForwarder.class);

    private static final String USAGE = "Usage: PortForwarder [-n namespace] <pod> <localPort>:<remotePort>";

    private final KubeClient client;
    private final String          namespace;
    private final String          pod;
    private final int             remotePort;
    private final ServerSocket    serverSocket;
    private final ExecutorService executor;
    private final Set<Closeable>  openResources = ConcurrentHashMap.newKeySet();

    public PortForwarder(KubeClient client, String namespace, String pod, int localPort, int remotePort)
    throws IOException {
        this.client       = client;
        this.namespace    = namespace;
        this.pod          = pod;
        this.remotePort   = remotePort;
        this.serverSocket = new ServerSocket(localPort, 50, InetAddress.getLoopbackAddress());
        this.executor     = Executors.newCachedThreadPool(daemonThreads());
        executor.submit(this::acceptLoop);
        logger.info("Forwarding localhost:{} -> {}/{}:{}", localPort(), namespace, pod, remotePort);
    }

    public int localPort() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    logger.warn("Failed to accept connection on localhost:{}", localPort(), e);
                }
            }
        }
    }

    /**
     * Each local connection gets its own port-forward stream. When either direction ends, both are torn down.
     */
    private void handle(Socket socket) {
        openResources.add(socket);
        PortForward.PortForwardResult forward = null;
        try {
            forward = client.portForward(namespace, pod, remotePort);
            openResources.add(forward);
            logger.debug("Connection from {} forwarded to {}/{}:{}",
                         socket.getRemoteSocketAddress(), namespace, pod, remotePort);

            InputStream  fromLocal = socket.getInputStream();
            OutputStream toLocal   = socket.getOutputStream();
            InputStream  fromPod   = forward.getInputStream(remotePort);
            OutputStream toPod     = forward.getOutboundStream(remotePort);
            InputStream  podErrors = forward.getErrorStream(remotePort);

            CountDownLatch finished = new CountDownLatch(1);
            executor.submit(() -> pipe(fromLocal, toPod, finished));
            executor.submit(() -> pipe(fromPod, toLocal, finished));
            executor.submit(() -> logErrors(podErrors));
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Failed to forward connection to {}/{}:{}", namespace, pod, remotePort, e);
        } finally {
            closeQuietly(socket);
            closeQuietly(forward);
        }
    }

    private static void pipe(InputStream in, OutputStream out, CountDownLatch finished) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Expected when the other direction closes the connection first.
            logger.debug("Stream closed", e);
        } finally {
            finished.countDown();
        }
    }

    /**
     * The API server reports failures, eg nothing listening on the pod's port, on the error channel.
     */
    private void logErrors(InputStream podErrors) {
        try (podErrors) {
            byte[] bytes = podErrors.readAllBytes();
            // the stream begins with the port, as two little-endian bytes, which is not an error
            int start = bytes.length >= 2 && ((bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8) == remotePort ? 2 : 0;
            String errors = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
            if (!errors.isBlank()) {
                logger.warn("Port-forward error from {}/{}:{}: {}", namespace, pod, remotePort, errors);
            }
        } catch (IOException e) {
            logger.debug("Error stream closed", e);
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        openResources.remove(closeable);
        try {
            closeable.close();
        } catch (IOException e) {
            logger.debug("Failed to close {}", closeable, e);
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "port-forward-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        logger.info("Closing port-forward localhost:{} -> {}/{}:{}", localPort(), namespace, pod, remotePort);
        try {
            serverSocket.close();
        } catch (IOException e) {
            logger.debug("Failed to close server socket", e);
        }
        openResources.forEach(this::closeQuietly);
        executor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        String namespace = KUBERNETES_NAMESPACE;
        String pod       = null;
        String ports     = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-n", "--namespace" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("Missing value for " + args[i - 1] + ". " + USAGE);
                    }
                    namespace = args[i];
                }
                default -> {
                    if (pod == null) {
                        pod = args[i];
                    } else if (ports == null) {
                        ports = args[i];
                    } else {
                        throw new IllegalArgumentException("Unexpected argument '" + args[i] + "'. " + USAGE);
                    }
                }
            }
        }
        if (pod == null || ports == null) {
            throw new IllegalArgumentException(USAGE);
        }
        String[] split      = ports.split(":");
        int      localPort  = Integer.parseInt(split[0]);
        int      remotePort = Integer.parseInt(split.length > 1 ? split[1] : split[0]);

        KubeClient     client   = new KubeClient(new FilePropertyProvider());
        PortForwarder  session  = new PortForwarder(client, namespace, pod, localPort, remotePort);
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            session.close();
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("Failed to close Kubernetes client", e);
            }
            shutdown.countDown();
        }));
        System.out.printf("Forwarding localhost:%d -> %s/%s:%d. Ctrl-C to stop.%n",
                          session.localPort(), namespace, pod, remotePort);
        shutdown.await();
    }
}
