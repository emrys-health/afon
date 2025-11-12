package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class DeploymentUtils {


    public static Deployment deploy(
            Construct scope,
            List<@NotNull ITerraformDependable> eksDependOn,
            Map<String, String> labels,
            List<DeploymentSpecTemplateSpecContainer> containers,
            List<DeploymentSpecTemplateSpecVolume> volumes,
            String name) {
        DeploymentSpecTemplateMetadata metadata = DeploymentSpecTemplateMetadata.builder().labels(labels).build();
        DeploymentSpecTemplate specTemplate = DeploymentSpecTemplate.builder()
                                                                    .metadata(metadata)
                                                                    .spec(DeploymentSpecTemplateSpec.builder()
                                                                                                    .container(containers)
                                                                                                    .volume(volumes)
                                                                                                    .build())
                                                                    .build();
        return Deployment.Builder.create(scope, name + "Deployment")
                                 .dependsOn(eksDependOn)
                                 .metadata(DeploymentMetadata.builder()
                                                             .name(name + "-deployment")
                                                             .labels(labels)
                                                             .build())
                                 .spec(DeploymentSpec.builder()
                                                     .selector(DeploymentSpecSelector.builder().matchLabels(labels).build())
                                                     .replicas("1")
                                                     .template(specTemplate)
                                                     .build())
                                 .build();
    }


}
