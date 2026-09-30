package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.lang.System.getenv;

/**
 *  Utility class to retrieve Afon runtime {@code afon.*} system properties,
 *  allowing these values to be overridden by the env counterparts {@code AFON_*}.
 */
public class ConfigUtils {

    /**
     * System env and property to retrieve Afon home dir
     */
    public static final String AFON_HOME_ENV = "AFON_HOME";
    public static final String AFON_HOME_PROPERTY = "afon.home";

    /**
     * System env and property to retrieve Afon cdktf dir
     */
    public static final String AFON_CDKTF_ENV = "AFON_CDKTF";
    public static final String AFON_CDKTF_PROPERTY = "afon.cdktf";

    /**
     * System property selecting Afon logging level
     */
    public static final String AFON_LOGGING_ENV = "AFON_LOGGING";
    public static final String AFON_LOGGING_PROPERTY = "afon.logging";

    /**
     * System property selecting Afon logging console level
     */
    public static final String AFON_LOGGING_CONSOLE_ENV = "AFON_LOGGING_CONSOLE";
    public static final String AFON_LOGGING_CONSOLE_PROPERTY = "afon.logging.console";

    /**
     * System property selecting a cloud provider
     */
    public static final String AFON_CLOUD_ENV = "AFON_CLOUD";
    public static final String AFON_CLOUD_PROPERTY = "afon.cloud";

    /**
     * System property {@link #AFON_CLOUD_PROPERTY} value for selecting all cloud providers.
     * The following will obtain the same result:
     * - Setting a blank value
     * - Not setting the system property
     */
    public static final String CLOUD_ALL = "ALL";

    /**
     * Returns the Afon home dir specified by env {@link #AFON_HOME_ENV} or system property {@link #AFON_HOME_PROPERTY}.
     * If none found, it defaults to $HOME/.afon
     *
     * @return the afon home dir
     */
    public static @NonNull String afonHome() {
        return envOrProperty(getenv(AFON_HOME_ENV), getProperty(AFON_HOME_PROPERTY, format("%s/%s", getenv("HOME"), ".afon")));
    }

    /**
     * Returns the Afon cdktf dir specified by env {@link #AFON_CDKTF_ENV} or system property {@link #AFON_CDKTF_PROPERTY}.
     * If none found, it defaults to {@link #afonHome()}/cdktf
     *
     * @return the afon cdktf dir
     */
    public static @NonNull String afonCkdtf() {
        return envOrProperty(getenv(AFON_CDKTF_ENV), getProperty(AFON_CDKTF_PROPERTY, format("%s/%s", afonHome(), "cdktf")));
    }

    /**
     * Returns the Afon cloud specified by env {@link #AFON_CLOUD_ENV} or system property {@link #AFON_CLOUD_PROPERTY}.
     * If none found, it defaults to "".
     *
     * @return the afon cloud
     */
    public static @NonNull String afonCloud() {
        return envOrProperty(getenv(AFON_CLOUD_ENV), getProperty(AFON_CLOUD_PROPERTY, ""));
    }

    /**
     * Returns the logging level specified by env {@link #AFON_LOGGING_ENV} or by system property {@link #AFON_LOGGING_PROPERTY}.
     * If none found, it defaults to INFO.
     *
     * @return the afon logging level
     */
    public static @NonNull String afonLoggingLevel() {
        return envOrProperty(getenv(AFON_LOGGING_ENV), getProperty(AFON_LOGGING_PROPERTY, "INFO"));
    }

    /**
     * Returns the logging level specified by env {@link #AFON_LOGGING_CONSOLE_ENV} or by system property  {@link #AFON_LOGGING_CONSOLE_PROPERTY}.
     * If none found, it defaults to OFF.
     *
     * @return the afon logging console level
     */
    public static @NonNull String afonLoggingConsoleLevel() {
        return envOrProperty(getenv(AFON_LOGGING_CONSOLE_ENV), getProperty(AFON_LOGGING_CONSOLE_PROPERTY, "OFF"));
    }

    /**
     * Returns the config dir located in {@link #afonHome()}/config.
     *
     * @return the config dir
     */
    public static @NonNull String configDir() {
        return format("%s/%s", afonHome(), "config");
    }

    /**
     * Returns the dir holding the stack dirs, located in {@link #afonCkdtf()}/stacks
     *
     * @return the stacks dir
     */
    public static @NonNull String stacksDir() {
        return format("%s/stacks", afonCkdtf());
    }

    /**
     * Returns the stack dir located in {@link #stacksDir()}/{@link StackId}, eg {@code stacks/aws-keycloak}
     *
     * @param stackId the {@link StackId}
     * @return the stack dir
     */
    public static @NonNull String stackDir(StackId stackId) {
        return format("%s/%s", stacksDir(), stackId);
    }

    /**
     * Returns the tfState path located in {@link #afonCkdtf()}/terraform.{@link StackId}.tfstate,
     * eg {@code terraform.aws-keycloak.tfstate}
     *
     * @param stackId the {@link StackId}
     * @return the tfState path
     */
    public static @NonNull String tfStatePath(StackId stackId) {
        return format("%s/terraform.%s.tfstate", afonCkdtf(), stackId);
    }

    /**
     * Returns the kubeconfig path located in {@link #afonCkdtf()}/kube/{@link StackId}.config,
     * eg {@code kube/aws-keycloak.config}, which the stack's own kubectl and helm commands use,
     * so that they never act on the cluster of another stack.
     *
     * @param stackId the {@link StackId}
     * @return the kubeconfig path
     */
    public static @NonNull String kubeConfigPath(StackId stackId) {
        return format("%s/kube/%s.config", afonCkdtf(), stackId);
    }

    /**
     * Returns the legacy stack dir located in {@link #stacksDir()}/{@link AfonCloud}, shared by all the stacks
     * of the cloud before each stack had its own, see {@link #stackDir(StackId)}.
     *
     * @param cloud the {@link AfonCloud}
     * @return the legacy stack dir
     */
    public static @NonNull String legacyStackDir(AfonCloud cloud) {
        return format("%s/%s", stacksDir(), cloud);
    }

    /**
     * Returns the legacy tfState path located in {@link #afonCkdtf()}/terraform.{@link AfonCloud}.tfstate,
     * shared by all the stacks of the cloud before each stack had its own, see {@link #tfStatePath(StackId)}.
     *
     * @param cloud the {@link AfonCloud}
     * @return the legacy tfState path
     */
    public static @NonNull String legacyTfStatePath(AfonCloud cloud) {
        return format("%s/terraform.%s.tfstate", afonCkdtf(), cloud);
    }

    /**
     * Returns the kubeconfig this deployment owns, located at {@link #afonHome()}/kubeconfig.{@code clusterName}.
     * <p>
     * The {@code kubectl} and {@code helm} commands in {@code local-exec} provisioners would otherwise read
     * the ambient {@code ~/.kube/config} and act on whatever context happened to be current — which, when more
     * than one cluster has been deployed from this machine, need not be the cluster the stack is applying to
     * or destroying. Giving each deployment its own file means those commands always target their own cluster,
     * and that switching context by hand between an apply and a destroy cannot redirect them.
     *
     * @param clusterName the cluster the kubeconfig addresses ({@code CLUSTER_NAME})
     * @return the kubeconfig path
     */
    public static @NonNull String kubeconfigPath(@NonNull String clusterName) {
        return format("%s/kubeconfig.%s", afonHome(), clusterName);
    }

    /**
     * Checks if system property {@link #AFON_CLOUD_PROPERTY} is set.
     * @return true if property value is not null, not blank and not equal (ignoring case) to {@link #CLOUD_ALL}.
     */
    public static boolean cloudPropertySet() {
        String value = getProperty(AFON_CLOUD_PROPERTY);
        return value != null && !value.isBlank() && !value.equalsIgnoreCase(CLOUD_ALL);
    }

    /**
     * Return system property {@link #AFON_CLOUD_PROPERTY}.
     * @return the property, whose value must be checked by {@link #cloudPropertySet()}.
     */
    public static String cloudPropertyValue() {
        return getProperty(AFON_CLOUD_PROPERTY);
    }

    /**
     * Return env is not {@code null}, else the property, which is assumed to be not {@code null}.
     *
     * @param env
     * @param property
     * @return
     */
    static @NonNull String envOrProperty(String env, String property) {
        return ( env != null ) ? env : property;
    }

    public static @NonNull AfonCatalog cloudToCatalog(AfonCloud cloud, AfonCatalogs catalogs) {
        return catalogs.get(cloud.toString());
    }
}
