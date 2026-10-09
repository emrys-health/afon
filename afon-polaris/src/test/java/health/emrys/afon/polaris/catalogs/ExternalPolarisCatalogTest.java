package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static health.emrys.afon.config.AfonProperty.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Polaris federating to a remote Polaris gets its token from KeyCloak, as the remote defers to it for OAuth and answers HTTP 501 to
 * its own token endpoint.
 */
class ExternalPolarisCatalogTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    {
        properties.setValue(OIDC_DOMAIN_NAME, "oauth.example");
        properties.setValue(OIDC_PROTOCOL, "https");
        properties.setValue(POLARIS_ID, "root");
        properties.setValue(POLARIS_SECRET, "secret");
        properties.setValue(AWS_POLARIS_DOMAIN_NAME, "polaris-aws.example");
        properties.setValue(AZURE_POLARIS_DOMAIN_NAME, "polaris-azure.example");
        properties.setValue(GCP_POLARIS_DOMAIN_NAME, "polaris-gcp.example");
        properties.setValue(AZURE_STORAGE_ACCOUNT, "account");
        properties.setValue(AZURE_TENANT_ID, "tenant");
        properties.setValue(AZURE_APP_ID, "app");
        properties.setValue(AWS_S3_ROLE_ARN, "arn:aws:iam::123456789012:role/test");
        properties.setValue(AWS_REGION, "eu-west-2");
        properties.setValue(GCP_SERVICE_ACCOUNT, "sa@project.iam.gserviceaccount.com");
    }

    private JsonNode oauthOf(BiFunction<AfonCatalog, AfonPropertyProvider, PolarisCatalog> factory, AfonCatalog catalog) throws Exception {
        JsonNode config = new ObjectMapper().readTree(factory.apply(catalog, properties).catalogConfig());
        return config.at("/connectionConfigInfo/authenticationParameters");
    }

    private void assertGetsItsTokenFromKeyCloak(JsonNode oauth) {
        assertThat(oauth.get("tokenUri").asText()).isEqualTo("https://oauth.example/realms/polaris/protocol/openid-connect/token");
        assertThat(oauth.get("scopes")).extracting(JsonNode::asText).containsExactly("openid");
    }

    @Test
    void azureExternalCatalogGetsItsTokenFromKeyCloak() throws Exception {
        assertGetsItsTokenFromKeyCloak(oauthOf(AzureExternalPolarisCatalog::new, new AfonCatalogs(properties).azureExternal()));
    }

    @Test
    void awsExternalCatalogGetsItsTokenFromKeyCloak() throws Exception {
        assertGetsItsTokenFromKeyCloak(oauthOf(AwsExternalPolarisCatalog::new, new AfonCatalogs(properties).get("aws_external")));
    }

    @Test
    void gcpExternalCatalogGetsItsTokenFromKeyCloak() throws Exception {
        assertGetsItsTokenFromKeyCloak(oauthOf(GcpExternalPolarisCatalog::new, new AfonCatalogs(properties).get("gcp_external")));
    }
}
