package health.emrys.afon.config;

import health.emrys.afon.ops.config.OpsDefaults;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.junit.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * TODO Move this to an acceptance test based on property settings?
 */
public class OpsPropertyProviderTest {

    @Test
    public void optionalPolarisNamesAreEqualToOpsDefaults() {
        AfonPropertyProvider provider = new OpsPropertyProvider();
        assertThat(provider.getValue(AfonProperty.POLARIS_NAMESPACE)).isEqualTo(OpsDefaults.POLARIS_NAMESPACE);
        assertThat(provider.getValue(AfonProperty.POLARIS_NAMESPACE_EXTERNAL)).isEqualTo(OpsDefaults.POLARIS_NAMESPACE_EXTERNAL);
        assertThat(provider.getValue(AfonProperty.POLARIS_TABLE_NAME)).isEqualTo(OpsDefaults.POLARIS_TABLE_NAME);
    }
}
