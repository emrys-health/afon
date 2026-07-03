package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import health.emrys.afon.cdk.SparkConfig;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.platform.JupyterWorkbenchDeployer;
import health.emrys.afon.platform.SparkEngineDeployer;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Properties;

/***
 * Creates the markup to create the full stack in AWS.
 * Note: it does not create anything other than JSON files!
 * You need to run tofu on those files for the infra to be provisioned.
 * Don't forget to
 * <UL>
 *     <LI>
 *          set environment variables, something like <pre>KUBE_CONFIG_PATH=/home/henryp/.kube/config</pre>
 *     </LI>
 *      <LI>
 *          <pre>unalias aws</pre> if you're using the Dockerized version and put the actual binary in your
 *          <pre>PATH</pre>
 *      </LI>
 * </UL>
 * when you run the output
 */
public class AwsWholeStack extends AwsKubernetesPolaris {

    static final Logger logger                     = org.slf4j.LoggerFactory.getLogger(AwsWholeStack.class);
    public static final String NO_INSTALLATION_FINALIZERS = "kubectl patch installation default -p " +
                                                     "'{\"metadata\":{\"finalizers\":[]}}' --type=merge";

    /**
     * Password the Jupyter VNC server is configured with. Kept in-cluster only — the
     * Guacamole web UI itself is intended to live behind oauth2-proxy.
     */
    protected final List<Service> loadBalancers;
    protected final SparkConfig   sparkOperatorConfig;
    protected final HelmProvider  helmProvider;

    public AwsWholeStack(final Construct scope,
                         final SupportedCloudsEnum id,
                         final Properties properties) {
        super(scope, id, properties);
        new JupyterWorkbenchDeployer().deployUsingProperties(this);
        loadBalancers = List.of(); // Jupyter is now exposed via ALB ingress, not a raw LoadBalancer Service

        hardenStack(); // provider pins + CSI driver + LB destroy handling, shared with the planner
        helmProvider = helmProvider();
        sparkOperatorConfig = new SparkEngineDeployer().installOperator(this);
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsWholeStack(app, ID, new AfonPropertiesLoader().getProperties());
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu " +
                    "-apply");
    }
}