package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.configs.GiteaConfig;

public class GitComponent implements StackComponent {
    @Override
    public void deploy(StackContext context) {
        CloudPlatform platform = context.platform();
        GiteaConfig   gitea    = new GiteaConfig(context.properties(), platform.cloud());
        gitea.deploy(context.stack(),
                     platform.configureHelm(),
                     platform.storageDependencies());
    }
}
