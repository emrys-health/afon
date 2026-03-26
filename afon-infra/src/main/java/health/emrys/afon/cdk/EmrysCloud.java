package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonProperties;

public interface EmrysCloud {
    AfonProperties.SupportedCloudsEnum getId();

    String getPolarisDomainName();
}
