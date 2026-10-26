package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.StackComponent;

import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.configs.TrinoWorkloads;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonCatalogs;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.ConfigUtils.cloudToCatalog;

/**
 * Trino, reading the cloud's catalogs from Polaris at the cloud's {@code POLARIS_DOMAIN_NAME}, with tokens from the cloud's
 * KeyCloak.
 */
public class TrinoComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        CloudPlatform platform = context.platform();
        // Trino only needs the cluster reachable, not the Spark operator.  The chart also creates a Service, which
        // fires the cloud's mutating webhooks, if it has any, which the service dependables wait for.
        List<ITerraformDependable> dependsOn = new ArrayList<>(platform.getDependables());
        dependsOn.addAll(platform.getServiceDependables());
        new TrinoWorkloads(context.properties(), context.users(),
                           context.properties().getCloudValue(platform.cloud(), AfonCloudProperty.POLARIS_DOMAIN_NAME),
                           context.properties().getOidcDomainName(platform.cloud()))
            .deploy(context.stack(), platform.configureHelm(),
                    cloudToCatalog(platform.cloud(), new AfonCatalogs(context.properties())), dependsOn);
    }
}
