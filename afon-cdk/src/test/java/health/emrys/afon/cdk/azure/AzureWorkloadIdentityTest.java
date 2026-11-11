package health.emrys.afon.cdk.azure;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConstantsPropertyProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AzureWorkloadIdentityTest {

    public static class Neither {
    }

    public static class BlankSecret {
        public static final String AZURE_APP_SECRET = "";
    }

    public static class FlagFalseBlankSecret {
        public static final String AZURE_WORKLOAD_IDENTITY = "false";
        public static final String AZURE_APP_SECRET = "";
    }

    public static class FlagTrueBlankSecret {
        public static final String AZURE_WORKLOAD_IDENTITY = "true";
        public static final String AZURE_APP_SECRET = "";
    }

    public static class FlagTrueNoSecret {
        public static final String AZURE_WORKLOAD_IDENTITY = "true";
    }

    public static class FlagTrueWithSecret {
        public static final String AZURE_WORKLOAD_IDENTITY = "true";
        public static final String AZURE_APP_SECRET = "secret";
    }

    public static class SecretOnly {
        public static final String AZURE_APP_SECRET = "secret";
    }

    private static AfonPropertyProvider provider(Class<?> constants) {
        return new ConstantsPropertyProvider(constants);
    }

    @Test
    public void isNotEnabledByDefaultNorFromBlankSecret() {
        assertThat(AzureWorkloadIdentity.isEnabled(provider(Neither.class))).isFalse();
        assertThat(AzureWorkloadIdentity.isEnabled(provider(BlankSecret.class))).isFalse();
        assertThat(AzureWorkloadIdentity.isEnabled(provider(FlagFalseBlankSecret.class))).isFalse();
    }

    @Test
    public void isEnabledWhenFlagTrue() {
        assertThat(AzureWorkloadIdentity.isEnabled(provider(FlagTrueBlankSecret.class))).isTrue();
    }

    @Test
    public void requiresSecretUnlessWorkloadIdentity() {
        assertThatThrownBy(() -> AzureWorkloadIdentity.validate(provider(BlankSecret.class)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AZURE_WORKLOAD_IDENTITY");
        assertThatCode(() -> AzureWorkloadIdentity.validate(provider(SecretOnly.class))).doesNotThrowAnyException();
        assertThatCode(() -> AzureWorkloadIdentity.validate(provider(FlagTrueNoSecret.class))).doesNotThrowAnyException();
        assertThatCode(() -> AzureWorkloadIdentity.validate(provider(FlagTrueWithSecret.class))).doesNotThrowAnyException();
    }

    @Test
    public void subjectIsThatOfThePolarisServiceAccount() {
        assertThat(AzureWorkloadIdentity.subject()).isEqualTo("system:serviceaccount:default:polaris");
    }
}
