package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;

public interface AfonCloudStack {
    AfonCloud getId();

    String getPolarisDomainName();
}
