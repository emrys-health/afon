package health.emrys.afon.spark;

public class Constants {

    /** The realm's URL, as its tokens name it in {@code iss}. */
    public static final String ISSUER_CONF = "spark.afon.connect.issuer";

    /** The realm's JWKS endpoint. */
    public static final String KEYS_URL_CONF = "spark.afon.connect.keysUrl";

    /**
     * The header that the Spark Connect server takes the client's OIDC token from, see SparkConnectGuardInterceptor of
     * afon-spark-additional: not Spark Connect's own {@code token}, which makes the client insist on TLS, which the server does not offer.
     */
    public static final String TOKEN_HEADER = "x-afon-token";

}
