package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;

public interface AfonCloudStack {
    AfonCloud getId();

    StackId getStackId();

    String getPolarisDomainName();
}
