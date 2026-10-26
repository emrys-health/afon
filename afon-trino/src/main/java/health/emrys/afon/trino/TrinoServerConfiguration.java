package health.emrys.afon.trino;

import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import org.jspecify.annotations.NonNull;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;
import static java.lang.String.format;

/**
 * The catalog files of a Trino that reads Polaris' catalogs.  They hold no credentials: Trino answers each query with the
 * token of whoever sent it, which the client passes as the extra credential {@value #TOKEN_EXTRA_CREDENTIAL}, eg
 * {@code extraCredentials=token:<access token>} in the JDBC URL or properties, and only starts each catalog, which calls
 * Polaris before any query has come, with the credential that Trino reads from the environment variable
 * {@value #CREDENTIAL_ENV}, see {@link #credential()}.
 */
public class TrinoServerConfiguration {

    /** The environment variable of the Trino pods that holds the credential with which each catalog starts. */
    public static final String CREDENTIAL_ENV         = "TRINO_POLARIS_CREDENTIAL";
    /** The extra credential, of a query, that holds the token with which Trino calls Polaris for it. */
    public static final String TOKEN_EXTRA_CREDENTIAL = "token";

    private final AfonPropertyProvider properties;
    private final String polarisDomainName;
    private final AfonUser user;

    public TrinoServerConfiguration(AfonPropertyProvider properties, String polarisDomainName, AfonUser user) {
        this.properties = properties;
        this.polarisDomainName = polarisDomainName;
        this.user = user;
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
     * @return the value of {@value #CREDENTIAL_ENV}: the user's client id and secret
     */
    public String credential() {
        return user.username() + ":" + user.password();
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
