package health.emrys.afon.trino;

import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import org.jspecify.annotations.NonNull;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;
import static java.lang.String.format;

public class TrinoServerConfiguration {

    private final AfonPropertyProvider properties;
    private final String polarisDomainName;
    private final AfonUser user;

    /**
     * Authenticates as the configured user, from users.csv, named by {@code USER_ID}.
     */
    public TrinoServerConfiguration(AfonPropertyProvider properties, AfonUserProvider users, String polarisDomainName) {
        this(properties, polarisDomainName, users.getConfiguredUser(properties));
    }

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
            iceberg.rest-catalog.oauth2.credential=%s:%s
            iceberg.rest-catalog.oauth2.scope=%s
            %s
            %s""",
            protocol, polarisDomainName,
            catalogName,
            user.username(), user.password(),
            getOauthScope(),
            fileSystemProperties(catalogName),
            additionalProperties()
            );
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
