package health.emrys.afon.config;

import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static health.emrys.afon.config.AfonProperty.*;
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

    private AfonPropertyProvider properties = new OpsPropertyProvider();

    @Test
    public void testWhitelistedAreValidDomainNames() {
        for (String domain : whitelistedDomains()) {
            assertThat(isRfc1123Domain(domain)).as("domain "+domain+" is valid").isTrue();
        }
    }

    @Test
    public void testBucketNameIsWhitelisted() {
        switch ( afonCloud() ){
            case "aws":
                checkPropertyIsWhitelisted(AWS_S3_BUCKET_NAME);
                break;
            case "azure":
                checkPropertyIsWhitelisted(AZURE_BUCKET_NAME);
                break;
//            case "gcp":
//                checkPropertyIsWhitelisted(GCP_BUCKET_NAME);
//                break;
            default:
                checkPropertyIsWhitelisted(AWS_S3_BUCKET_NAME);
                checkPropertyIsWhitelisted(AZURE_BUCKET_NAME);
//                checkPropertyIsWhitelisted(GCP_BUCKET_NAME)
                ;
        }
    }

    private String[] whitelistedDomains() {
        return properties.getValue(ALLOWED_DOMAINS_CSV, "").split(",");
    }

    private void checkPropertyIsWhitelisted(AfonProperty property) {
        String value = properties.getValue(property);
        String[] whitelistedDomains = whitelistedDomains();
        boolean found = false;
        for (String domain : whitelistedDomains) {
            found |= domain.contains(value);
        }
        assertThat(found).as(format("%s not found in %s", value, String.join(", ", whitelistedDomains))).isTrue();
    }

}
