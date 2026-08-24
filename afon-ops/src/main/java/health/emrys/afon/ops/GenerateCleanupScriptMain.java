package health.emrys.afon.ops;

import health.emrys.afon.cdk.CleanupHelper;
import health.emrys.afon.cdk.aws.AwsCleanupHelper;
import health.emrys.afon.cdk.azure.AzureCleanupHelper;
import health.emrys.afon.cdk.gcp.GcpCleanupHelper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static health.emrys.afon.config.ConfigUtils.*;

/**
 * Generate the CleanupHelper script for the given cloud.
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

    public void generateCleanupScript(AfonCloud cloud) {
        invoke(CLOUD_TO_HELPER.get(cloud), String.format("%s/%s-cleanup.sh", afonHome(), cloud.name().toLowerCase()));
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
            System.out.printf("%n%s requires argument: cloud%n", GenerateCleanupScriptMain.class.getSimpleName());
            return;
        }
        AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
        var properties = new FilePropertyProvider();
        new GenerateCleanupScriptMain(properties).generateCleanupScript(cloud);
    }


}
