package health.emrys.afon.cdk.aws;

import health.emrys.afon.cdk.TrinoWorkloads;
import health.emrys.afon.config.AfonProperties;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.Properties;

public class TrinoClusterDeployment extends SparkClusterDeployment {
    public TrinoClusterDeployment(@NotNull Construct scope, AfonProperties.@NotNull SupportedCloudsEnum id, Properties properties) {
        super(scope, id, properties);
        // Trino only needs the cluster reachable (not the Spark operator), so it depends on the
        // EKS dependables rather than eksDependOn.
        new TrinoWorkloads(properties, polarisDomainName())
            .deploy(this, helmProvider, id, cloudProvider.getDependables());
    }
}
