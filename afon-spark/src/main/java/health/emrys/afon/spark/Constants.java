package health.emrys.afon.spark;

public class Constants {

    /** The realm's URL, as its tokens name it in {@code iss}. */
    public static final String ISSUER_CONF = "spark.afon.connect.issuer";

    /** The realm's JWKS endpoint. */
    public static final String KEYS_URL_CONF = "spark.afon.connect.keysUrl";

}
