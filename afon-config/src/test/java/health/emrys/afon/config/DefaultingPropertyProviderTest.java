package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DefaultingPropertyProviderTest {

    static class ProviderConstants {
        public static final String CLUSTER_NAME = "provider-cluster";
    }

    static class DefaultConstants {
        public static final String CLUSTER_NAME = "default-cluster";
        public static final String AWS_REGION = "default-region";
    }

    private AfonPropertyProvider provider = new ConstantsPropertyProvider(ProviderConstants.class);
    private AfonPropertyProvider defaults = new ConstantsPropertyProvider(DefaultConstants.class);
    private AfonPropertyProvider defaulting = new DefaultingPropertyProvider(provider, defaults);

    @Test
    public void declaredPropertyOverridesDefault() {
        assertThat(defaulting.hasProperty(AfonProperty.CLUSTER_NAME)).isTrue();
        assertThat(defaulting.getValue(AfonProperty.CLUSTER_NAME)).isEqualTo(ProviderConstants.CLUSTER_NAME);
    }

    @Test
    public void fallsBackToDefaultWhenNotDeclared() {
        assertThat(defaulting.hasProperty(AfonProperty.AWS_REGION)).isTrue();
        assertThat(defaulting.getValue(AfonProperty.AWS_REGION)).isEqualTo(DefaultConstants.AWS_REGION);
    }

    @Test
    public void missingFromBothIsAbsent() {
        assertThat(defaulting.hasProperty(AfonProperty.AZURE_REGION)).isFalse();
        assertThat(defaulting.getValue(AfonProperty.AZURE_REGION, "fallback")).isEqualTo("fallback");
        assertThatThrownBy(() -> defaulting.getValue(AfonProperty.AZURE_REGION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void setValueDelegatesToProviderOnly() {
        String azureRegion = "azure-region";
        defaulting.setValue(AfonProperty.AZURE_REGION, azureRegion);
        assertThat(defaulting.getValue(AfonProperty.AZURE_REGION)).isEqualTo(azureRegion);
        assertThat(provider.hasProperty(AfonProperty.AZURE_REGION)).isTrue();
        assertThat(defaults.hasProperty(AfonProperty.AZURE_REGION)).isFalse();
    }

    @Test
    public void secretPropertiesDelegateToProviderOnly() {
        assertThat(defaulting.getSecretProperties()).isEqualTo(provider.getSecretProperties());
    }

}
