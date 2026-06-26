package health.emrys.afon.trino;

import health.emrys.afon.config.AfonProperties;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.CatalogUtils.OAUTH_SCOPE;
import static java.lang.String.format;

public class TrinoServerConfiguration {

    private final String polarisDomainName;
    private final String awsRegion;
    private final String polarisClientId;
    private final String polarisSecret;

    public TrinoServerConfiguration(String polarisDomainName,
                                    Properties properties) {
        this.polarisDomainName = polarisDomainName;
        this.awsRegion         = AWS_REGION.getValueFrom(properties);
        this.polarisClientId   = USER_POLARIS_ID.getValueFrom(properties);
        this.polarisSecret     = USER_POLARIS_SECRET.getValueFrom(properties);
    }

    public String catalogProperties(AfonProperties.SupportedCloudsEnum cloud) {
        String protocol = "https";
        if (polarisDomainName.toLowerCase().contains("localhost")) {
            protocol = "http";
        }
        return format(
            """
            connector.name=iceberg
            iceberg.catalog.type=rest
            iceberg.rest-catalog.uri=%S://%s/api/catalog/
            iceberg.rest-catalog.warehouse=%s
            iceberg.rest-catalog.vended-credentials-enabled=true
            iceberg.rest-catalog.security=OAUTH2
            iceberg.rest-catalog.oauth2.credential=%s:%s
            iceberg.rest-catalog.oauth2.scope=%s
            %s""",
            protocol,
            polarisDomainName,
            cloud,
            polarisClientId,
            polarisSecret,
            OAUTH_SCOPE,
            fileSystemProperties(cloud));
    }

    /**
     * The object-store plugin Trino uses to read a warehouse's data files; vended credentials
     * from Polaris supply the actual access. Selected from the cloud-name prefix.
     */
    private String fileSystemProperties(AfonProperties.SupportedCloudsEnum cloud) {
        String name = cloud.toString();
        if (name.startsWith("aws")) {
            return format("fs.s3.enabled=true%ns3.region=%s%n", awsRegion);
        }
        if (name.startsWith("azure")) {
            return "fs.azure.enabled=true\n";
        }
        if (name.startsWith("gcp")) {
            // see https://github.com/trinodb/trino/issues/26772
            return "fs.gcs.enabled=true\ngcs.auth-type=APPLICATION_DEFAULT\n";
        }
        return "";
    }
}
