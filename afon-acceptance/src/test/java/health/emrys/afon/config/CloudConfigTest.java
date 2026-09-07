package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.regex.Pattern;

import static health.emrys.afon.config.AfonProperty.*;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class CloudConfigTest {
    private static final Pattern RFC_1123_DOMAIN = Pattern.compile(
        "^(?=.{1,253}\\.?$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*"
        + "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"
    );

    public static boolean isRfc1123Domain(String domain) {
        return domain != null && RFC_1123_DOMAIN.matcher(domain).matches();
    }

    @Test
    public void testWhitelistedAreValidDomainNames() {
        for (String domain : getWhitelistedDomains()) {
            assertThat(isRfc1123Domain(domain)).as(domain).isTrue();
        }
    }

    @Test
    public void testAwsBucketIsWhitelisted() {
        checkPropertiesContain(AWS_S3_BUCKET_NAME.getValueFrom(getProperties()));
    }

    @Test
    public void testAzueBucketIsWhitelisted() {
        checkPropertiesContain(AZURE_BUCKET.getValueFrom(getProperties()));
    }

    @Test
    public void testGcpBucketIsWhitelisted() {
        checkPropertiesContain(GCP_BUCKET_NAME.getValueFrom(getProperties()));
    }

    private String[] getWhitelistedDomains() {
        String     allowed    = ALLOWED_DOMAINS_CSV.getValueFrom(getProperties());
        String[]   domains    = allowed.split(",");
        return domains;
    }

    private static Properties getProperties() {
        return new FilePropertyProvider().getProperties();
    }

    private void checkPropertiesContain(String value) {
        boolean found = false;
        for (String domain : getWhitelistedDomains()) {
            found |= domain.contains(value);
        }
        assertThat(found).as(format("%s not found in %s", value, String.join(", ", getWhitelistedDomains()))).isTrue();
    }

}
