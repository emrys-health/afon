package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.OIDC_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.OIDC_ISSUER_URL;
import static org.assertj.core.api.Assertions.assertThat;

class CloudPropertyProviderTest {

    static class Constants {
        public static final String OIDC_DOMAIN_NAME = "oauth.example";
        public static final String OIDC_ISSUER_URL = "https://[OIDC_DOMAIN_NAME]/realms/polaris";
        public static final String AWS_OIDC_DOMAIN_NAME = "oauth.aws.example";
        public static final String AWS_REGION = "eu-west-2";
    }

    private final AfonPropertyProvider properties = new ConstantsPropertyProvider(Constants.class);

    @Test
    void readsTheOidcDomainAndIssuerOfTheCloud() {
        AfonPropertyProvider aws = properties.forCloud(AWS);
        assertThat(aws.getValue(OIDC_DOMAIN_NAME)).isEqualTo("oauth.aws.example");
        assertThat(aws.getValue(OIDC_ISSUER_URL)).isEqualTo("https://oauth.aws.example/realms/polaris");
        assertThat(aws.getProperties().getProperty("OIDC_DOMAIN_NAME")).isEqualTo("oauth.aws.example");
    }

    @Test
    void readsTheNeutralOnesForACloudWithNoneOfItsOwn() {
        assertThat(properties.forCloud(AZURE).getValue(OIDC_DOMAIN_NAME)).isEqualTo("oauth.example");
    }

    @Test
    void readsEverythingElseAsTheDecoratedProviderDoes() {
        assertThat(properties.forCloud(AWS).getValue(AWS_REGION)).isEqualTo("eu-west-2");
    }

    @Test
    void stillAnswersForAnotherCloud() {
        assertThat(properties.forCloud(AWS).getOidcDomainName(AZURE)).isEqualTo("oauth.example");
    }
}
