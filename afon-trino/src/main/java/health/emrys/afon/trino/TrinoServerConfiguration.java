package health.emrys.afon.trino;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import org.jspecify.annotations.NonNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;
import static java.lang.String.format;

/**
 * The catalog files of a Trino that reads Polaris' catalogs.  They hold no credentials: Trino answers each query with the
 * token of whoever sent it, which the client passes as the extra credential {@value #TOKEN_EXTRA_CREDENTIAL}, eg
 * {@code extraCredentials=token:<access token>} in the JDBC URL or properties, and only starts each catalog, which calls
 * Polaris before any query has come, with the credential that Trino reads from the environment variable
 * {@value #CREDENTIAL_ENV}, see {@link #credential()}.
 * <p>
 * The client must also authenticate to Trino with the same token, as its access token (the {@code Authorization: Bearer}
 * header, eg {@code accessToken=<access token>} in JDBC), and may only run queries as the user it names: Trino does not pass
 * that header on to Polaris, and the Iceberg client caches each user's Polaris session by user, not by token, so a user
 * that Trino did not authenticate could otherwise use another's.
 */
public class TrinoServerConfiguration {

    /**
     * The environment variable of the Trino pods that holds the credential with which each catalog starts.
     */
    public static final String CREDENTIAL_ENV         = "TRINO_POLARIS_CREDENTIAL";
    /**
     * The extra credential, of a query, that holds the token with which Trino calls Polaris for it.
     */
    public static final String TOKEN_EXTRA_CREDENTIAL = "token";
    /**
     * The environment variable of the Trino pods that holds the secret with which the coordinator and workers authenticate
     * one another, which Trino requires once clients are authenticated, see {@link #sharedSecret()}.
     */
    public static final String SHARED_SECRET_ENV      = "TRINO_SHARED_SECRET";

    private final AfonPropertyProvider properties;
    private final String               polarisDomainName;
    private final AfonUser             user;

    /**
     * @param user This is an admin user that allows Trino to talk to Polaris (with the associated JWT) as it appears
     *             that Trino eagerly checks the configured catalogs, not lazily. It's an architectural decision so it
     *             can fail fast if something is amiss.
     */
    public TrinoServerConfiguration(AfonPropertyProvider properties,
                                    String polarisDomainName,
                                    AfonUser user) {
        this.properties        = properties;
        this.polarisDomainName = polarisDomainName;
        this.user              = user;
    }

    public String catalogProperties(String catalogName) {
        String protocol = "https://";
        if (polarisDomainName.toLowerCase().contains("localhost")
            || polarisDomainName.toLowerCase().contains("127.0.0.1")) {
            protocol = "http://";
        }
        if (polarisDomainName.toLowerCase().startsWith("http:") // if the domain name already contains the protocol, use it
            || polarisDomainName.toLowerCase().startsWith("https:")) {
            protocol = "";
        }
        return format(
            """
            connector.name=iceberg
            iceberg.catalog.type=rest
            iceberg.rest-catalog.view-endpoints-enabled=false
            iceberg.rest-catalog.uri=%s%s/api/catalog/
            iceberg.rest-catalog.warehouse=%s
            iceberg.rest-catalog.vended-credentials-enabled=true
            iceberg.rest-catalog.security=OAUTH2
            iceberg.rest-catalog.session=USER
            iceberg.rest-catalog.oauth2.credential=${ENV:%s}
            iceberg.rest-catalog.oauth2.scope=%s
            %s
            %s""",
            protocol, polarisDomainName,
            catalogName,
            CREDENTIAL_ENV,
            getOauthScope(),
            fileSystemProperties(catalogName),
            additionalProperties()
        );
    }

    /**
     * The placeholder with which a Data Provider's catalog starts: the catalog needs some static authentication to be valid, and the Data
     * Provider's Polaris takes only a member's own token, which Trino passes for each query, so nothing the server holds can be a credential
     * of it.  It is only ever sent to the proxy that serves the catalog (see {@link #dataProviderCatalogProperties}), which does not check it.
     */
    static final String STARTUP_TOKEN = "no-token-until-a-member-queries";

    /**
     * The file of a catalog that a Data Provider serves, which a TRE project reads, see {@link AfonCatalogs.Source#DATA_PROVIDER}: the name the
     * catalog has at the Data Provider, and the storage plugin of the Data Provider's cloud, not the TRE's.  It holds none of the server's
     * credentials, as the Data Provider's Polaris takes only the token of the member who queries, which it checks for its audience, its second factor
     * and its project, and which Trino passes on as for any catalog (see {@link #TOKEN_EXTRA_CREDENTIAL}), so that no OAuth server of this stack is
     * involved.
     *
     * <p>Trino's client calls the catalog's endpoint to configure it before any query, with the static authentication of the catalog, which the
     * Data Provider's Polaris would refuse, so the endpoint is that of a proxy in the TRE that answers that call and passes the rest on, with the
     * member's token, to the Data Provider's.
     *
     * @param catalog a catalog from {@link AfonCatalogs.Source#DATA_PROVIDER}
     * @param uri     the endpoint that Trino calls for it, the proxy's
     */
    public String dataProviderCatalogProperties(AfonCatalog catalog, String uri) {
        if (catalog.source() != AfonCatalogs.Source.DATA_PROVIDER) {
            throw new IllegalArgumentException("Catalog '" + catalog.id() + "' is not from a Data Provider, but from the " + catalog.source());
        }
        return format(
            """
            connector.name=iceberg
            iceberg.catalog.type=rest
            iceberg.rest-catalog.view-endpoints-enabled=false
            iceberg.rest-catalog.uri=%s
            iceberg.rest-catalog.warehouse=%s
            iceberg.rest-catalog.vended-credentials-enabled=true
            iceberg.rest-catalog.security=OAUTH2
            iceberg.rest-catalog.session=USER
            iceberg.rest-catalog.oauth2.token=%s
            %s""",
            uri,
            catalog.name(), // the Data Provider's Polaris catalog name
            STARTUP_TOKEN,
            fileSystemProperties(catalog.cloud().toString())
        );
    }

    /**
     * @return the value of {@value #CREDENTIAL_ENV}: the user's client id and secret
     */
    public String credential() {
        return user.username() + ":" + user.password();
    }

    /**
     * @return the value of {@value #SHARED_SECRET_ENV}: derived from {@link #credential()}, so that it is secret, and the
     * same on every deploy, as a new one would restart the pods, without a property of its own
     */
    public String sharedSecret() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(credential().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal("trino-internal-communication".getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not derive Trino's shared secret", e);
        }
    }

    protected String additionalProperties() {
        return "";
    }

    protected @NonNull String getOauthScope() {
        return OAUTH_SCOPE;
    }

    /**
     * The object-store plugin Trino uses to read a warehouse's data files; vended credentials
     * from Polaris supply the actual access. Selected from the cloud-name prefix.
     */
    private String fileSystemProperties(String catalogName) {
        if (catalogName.startsWith("aws")) {
            return format("fs.s3.enabled=true%ns3.region=%s%n", properties.getValue(AWS_REGION));
        }
        if (catalogName.startsWith("azure")) {
            return "fs.azure.enabled=true\n";
        }
        if (catalogName.startsWith("gcp")) {
            // see https://github.com/trinodb/trino/issues/26772
            return "fs.gcs.enabled=true\ngcs.auth-type=APPLICATION_DEFAULT\n";
        }
        return "";
    }
}
