package health.emrys.afon.config;

import health.emrys.afon.cdk.configs.WhitelistConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static health.emrys.afon.config.ConfigUtils.afonCloud;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TODO Move this to an acceptance test based on the specific cloud?
 */
public class CloudConfigTest {
    private static final Pattern RFC_1123_DOMAIN = Pattern.compile(
        "^(?=.{1,253}\\.?$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*"
        + "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"
    );

    private static boolean isRfc1123Domain(String domain) {
        return domain != null && RFC_1123_DOMAIN.matcher(domain).matches();
    }

    public static final String AWS_BUCKET_NAME   = "AWS_BUCKET_NAME";
    public static final String AZURE_BUCKET_NAME = "AZURE_BUCKET_NAME";
    public static final String GCP_BUCKET_NAME   = "GCP_BUCKET_NAME";

    private AfonPropertyProvider properties = new FilePropertyProvider(); // MUST test real properties to prevent insecure code from being deplopyed DO NOT CHANGE!

    @Test
    public void testWhitelistedAreValidDomainNames() {
        for (String domain : whitelistedDomains()) {
            assertThat(isRfc1123Domain(domain)).as("domain " + domain + " is valid").isTrue();
        }
    }

    @Test
    public void testBucketNameIsWhitelisted() {
        // Base bucket names are no longer typed AfonProperty entries for any cloud (see
        // AWS_BUCKET_NAME_aws / AZURE_BUCKET_NAME_azure / GCP_BUCKET_NAME_gcp in
        // catalogs-<cloud>.properties).
        switch (afonCloud()) {
            case "aws":
                checkPropertyIsWhitelisted(AWS_BUCKET_NAME, "aws");
                break;
            case "azure":
                checkPropertyIsWhitelisted(AZURE_BUCKET_NAME, "azure");
                break;
            case "gcp":
                checkPropertyIsWhitelisted(GCP_BUCKET_NAME, "gcp");
                break;
            default:
                checkPropertyIsWhitelisted(AWS_BUCKET_NAME, "aws");
                checkPropertyIsWhitelisted(AZURE_BUCKET_NAME, "azure");
                checkPropertyIsWhitelisted(GCP_BUCKET_NAME, "gcp");
        }
    }

    private String[] whitelistedDomains() {
        return new WhitelistConfig(properties).domains().toArray(new String[0]);
    }

    private void checkPropertyIsWhitelisted(String propertyName, String catalogId) {
        String   value              = properties.getCatalogProperty(propertyName, catalogId);
        String[] whitelistedDomains = whitelistedDomains();
        boolean  found              = false;
        for (String domain : whitelistedDomains) {
            found |= domain.contains(value);
        }
        assertThat(found).as(format("%s not found in %s", value, String.join(", ", whitelistedDomains))).isTrue();
    }

    @Test
    public void testNoCruftInPropertiesFiles() {
        List<String> unknown = new ArrayList<>();
        for (Iterator<Object> it = properties.getProperties().keys().asIterator(); it.hasNext(); ) {
            String key = it.next().toString();
            if (!key.contains(key.substring(0, 3).toLowerCase()) // catalog metadata
                && !key.endsWith("CATALOG_IDS")
            ) {
                try {
                    AfonProperty.valueOf(key);
                } catch (IllegalArgumentException e) {
                    unknown.add(key);
                }
            }
        }
        assertThat(unknown).isEmpty();
    }
}
