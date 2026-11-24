package health.emrys.afon.spark.guard;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sparkproject.connect.grpc.ForwardingServerCallListener;
import org.sparkproject.connect.grpc.Metadata;
import org.sparkproject.connect.grpc.ServerCall;
import org.sparkproject.connect.grpc.ServerCallHandler;
import org.sparkproject.connect.grpc.ServerInterceptor;
import org.sparkproject.connect.grpc.Status;
import org.sparkproject.connect.protobuf.Message;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Optional;

import static health.emrys.afon.spark.Constants.*;

/**
 * Lets into the Spark Connect server only a client with an OIDC token of the realm, and only what it may do there.  Register it
 * with {@code spark.connect.grpc.interceptor.classes}.
 *
 * <p>Spark Connect checks nobody: any client that reaches it gets a session, as whatever user it says it is, and runs code beside
 * every other user's.  So every call must carry the client's own token, in the header TOKEN_HEADER, which a client sets
 * by adding {@code ;x-afon-token=<token>} to its {@code sc://} connection string, which is checked as Trino checks it
 * (see {@link TokenVerifier}).  Not the standard {@code token} parameter, which makes the client insist on TLS, which the server
 * does not offer.  A call without a token that is good is refused, as unauthenticated.
 *
 * <p>Each request is then made as the token's subject, whatever user the client said it was, so that sessions are keyed by who is
 * proven to have made them, and no client can reach another user's session, even knowing its id.  And a request with JVM code in
 * it is refused, as permission denied, see {@link RequestPolicy}.
 *
 * <p>The realm is configured by ISSUER_CONF and KEYS_URL_CONF: if either is missing, the server does not start.
 */
public class SparkConnectGuardInterceptor implements ServerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectGuardInterceptor.class);

    static final Metadata.Key<String> TOKEN = Metadata.Key.of(TOKEN_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    static final String UNAUTHENTICATED = "Spark Connect needs your OIDC token: add ;" + TOKEN_HEADER
                                          + "=<token> to the sc:// connection string, eg sc://<host>:15002/;" + TOKEN_HEADER + "=<token>";

    private final TokenVerifier verifier;

    /** As Spark creates it, from the configuration of the server. */
    public SparkConnectGuardInterceptor() {
        this(verifierOf(SparkEnv.get().conf()));
    }

    SparkConnectGuardInterceptor(TokenVerifier verifier) {
        this.verifier = verifier;
    }

    static TokenVerifier verifierOf(SparkConf conf) {
        String issuer  = conf.get(ISSUER_CONF);
        String keysUrl = conf.get(KEYS_URL_CONF);
        try {
            return new TokenVerifier(issuer, URI.create(keysUrl).toURL());
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException(KEYS_URL_CONF + " is not a URL: " + keysUrl, e);
        }
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String user;
        try {
            user = verifier.subject(headers.get(TOKEN));
        } catch (TokenVerifier.BadTokenException e) {
            // never the token itself
            logger.warn("Refused {}: {}", call.getMethodDescriptor().getFullMethodName(), e.getMessage());
            call.close(Status.UNAUTHENTICATED.withDescription(UNAUTHENTICATED + " (" + e.getMessage() + ")"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return new Guarded<>(next.startCall(call, headers), call, user);
    }

    /** What reaches the server of a call that is authenticated: each request as the user, and nothing after one that is refused. */
    private static final class Guarded<Q, R> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<Q> {

        private final ServerCall<Q, R> call;
        private final String           user;
        private       boolean          refused;

        Guarded(ServerCall.Listener<Q> delegate, ServerCall<Q, R> call, String user) {
            super(delegate);
            this.call = call;
            this.user = user;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onMessage(Q request) {
            if (refused) {
                return;
            }
            if (!(request instanceof Message message)) {
                refuse(Status.INTERNAL, "the request is not one of Spark Connect's");
                return;
            }
            Optional<String> refusal;
            Message          asUser;
            try {
                refusal = RequestPolicy.refusal(message);
                asUser  = RequestPolicy.asUser(message, user);
            } catch (RuntimeException e) {
                logger.warn("Could not check {} of {}", call.getMethodDescriptor().getFullMethodName(), user, e);
                refuse(Status.INTERNAL, "the request could not be checked");
                return;
            }
            if (refusal.isPresent()) {
                refuse(Status.PERMISSION_DENIED, refusal.get());
                return;
            }
            super.onMessage((Q) asUser);
        }

        /** The server is told nothing of a call that is refused, which it would answer on a call that is closed. */
        @Override
        public void onHalfClose() {
            if (!refused) {
                super.onHalfClose();
            }
        }

        @Override
        public void onReady() {
            if (!refused) {
                super.onReady();
            }
        }

        private void refuse(Status status, String why) {
            refused = true;
            logger.warn("Refused {} of {}: {}", call.getMethodDescriptor().getFullMethodName(), user, why);
            call.close(status.withDescription(why), new Metadata());
        }
    }
}
