package health.emrys.afon.ops.net;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Looks up the public IP address this machine's traffic appears to come from, as seen by
 * <a href="https://ifconfig.me">ifconfig.me</a>. Useful for putting the caller on an allow-list
 * such as {@code AWS_POLARIS_ALLOWED_CIDRS}.
 */
public class PublicIp {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PublicIp.class);

    private static final URI      ENDPOINT = URI.create("https://ifconfig.me/ip");
    private static final Duration TIMEOUT  = Duration.ofSeconds(10);
    // IPv4 dotted quad or anything IPv6-shaped; enough to reject an HTML error page.
    private static final Pattern  IP       = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F:]+)$");

    public static String get() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                                      .connectTimeout(TIMEOUT)
                                      .build();
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                                         .timeout(TIMEOUT)
                                         // ifconfig.me serves plain text to curl-like clients
                                         .header("User-Agent", "curl/8")
                                         .GET()
                                         .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ifconfig.me returned HTTP " + response.statusCode());
        }
        String ip = response.body().trim();
        if (!IP.matcher(ip).matches()) {
            throw new IllegalStateException("ifconfig.me returned something that is not an IP: " + ip);
        }
        logger.info("Public IP is {}", ip);
        return ip;
    }

    public static void main(String[] args) {
        try {
            new PublicIp().get();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not reach " + ENDPOINT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + ENDPOINT, e);
        }
    }
}
