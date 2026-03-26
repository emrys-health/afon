package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;

public interface EmrysCloud {
    SupportedCloudsEnum getId();

    String getPolarisDomainName();
}
