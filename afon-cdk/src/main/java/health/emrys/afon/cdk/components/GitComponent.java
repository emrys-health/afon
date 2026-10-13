package health.emrys.afon.cdk.components;

import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.RetainedVolumeClaim;
import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.configs.GiteaConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GitComponent implements StackComponent {
    @Override
    public void deploy(StackContext context) {
        CloudPlatform platform = context.platform();
        GiteaConfig   gitea    = new GiteaConfig(context.properties(), platform.cloud(), platform.clusterName());
        // The repositories are kept on a volume that outlives the cluster, where the platform has them.
        Optional<RetainedVolumeClaim> claim = platform.retainedVolumeClaim("gitea", gitea.storageSize(), "default");
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.storageDependencies());
        claim.ifPresent(retained -> dependsOn.add(retained.claim()));
        gitea.deploy(context.stack(),
                     platform.configureHelm(),
                     dependsOn,
                     claim.map(RetainedVolumeClaim::name));
    }
}
