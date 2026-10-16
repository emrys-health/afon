package health.emrys.afon.ops;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.cdk.CleanupHelper;
import health.emrys.afon.cdk.aws.AwsCleanupHelper;
import health.emrys.afon.cdk.azure.AzureCleanupHelper;
import health.emrys.afon.cdk.gcp.GcpCleanupHelper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static health.emrys.afon.config.ConfigUtils.afonHome;

/**
 * Generates the CleanupHelper script for the given stack, {@code <stack-id>}, into
 * {@code <afon.home>/<stack-id>-cleanup.sh}.
 */
public class GenerateCleanupScriptMain {
    static final  Logger logger = org.slf4j.LoggerFactory.getLogger(GenerateCleanupScriptMain.class);

    private static final Map<AfonCloud, Class<? extends CleanupHelper>>
            CLOUD_TO_HELPER = Map.of(
            AfonCloud.AWS, AwsCleanupHelper.class,
            AfonCloud.AZURE, AzureCleanupHelper.class,
            AfonCloud.GCP, GcpCleanupHelper.class);

    private final AfonPropertyProvider properties;

    public GenerateCleanupScriptMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public void generateCleanupScript(StackId stackId) {
        invoke(CLOUD_TO_HELPER.get(stackId.cloud()), stackId.toString(), cleanupScriptPath(stackId));
    }

    static String cleanupScriptPath(StackId stackId) {
        return String.format("%s/%s-cleanup.sh", afonHome(), stackId);
    }

    private static void invoke(Class<?> target, String... args) {
        try {
            Method main = target.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            logger.error("Command {} failed", target.getSimpleName(), e.getCause());
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }


    public static final void main(String[] args) {
        if (args.length == 0) {
            String usage = String.format("""
                                        
                    Invalid arguments: '%s'
                    usage:  %s requires argument: <stack-id>, eg aws-keycloak
                    """, String.join(" ", args), GenerateCleanupScriptMain.class.getSimpleName());
            logger.warn(usage);
            return;
        }
        StackId stackId = StackDefinitions.resolve(StackId.parse(args[0]));
        var properties = new OpsPropertyProvider();
        new GenerateCleanupScriptMain(properties).generateCleanupScript(stackId);
    }


}
