package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

import static java.lang.String.format;

public class ConfigUtils {
    /**
     * System property selecting a given cloud provider.
     */
    public static final String CLOUD_SYSTEM_PROPERTY = "afon.cloud";
    /**
     * System property {@link #CLOUD_SYSTEM_PROPERTY} value for selecting all cloud providers.
     * The following will obtain the same result:
     *
     * - Setting a blank value
     * - Not setting the system property
     */
    public static final String CLOUD_ALL = "ALL";

    /**
     * Returns the dir specified by system property ${afon.home}.
     * If not found it defaults to $HOME/.afon
     *
     * @return the afon home dir
     */
    public static @NonNull String afonHome() {
        return System.getProperty("afon.home", format("%s/%s", System.getenv("HOME"), ".afon"));
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
     * Returns the cdktf dir located in {@link #afonHome()}/cdktf.
     *
     * @return the cdktf dir
     */
    public static @NonNull String cdktfDir() {
        return format("%s/%s", afonHome(), "cdktf");
    }

    /**
     * Returns the stack dir located in {@link #cdktfDir()}/stacks/{@link AfonCloud#name()}
     *
     * @param id the {@link AfonCloud}
     * @return the stack dir
     */
    public static @NonNull String stackDir(AfonCloud id) {
        return format("%s/stacks/%s", cdktfDir(), id);
    }

    /**
     * Returns the tfState path located in {@link #cdktfDir()}/terraform.{@link AfonCloud#name()}.tfstate
     *
     * @param id the {@link AfonCloud}
     * @return the stack dir
     */
    public static @NonNull String tfStatePath(AfonCloud id) {
        return format("%s/terraform.%s.tfstate", cdktfDir(), id);
    }

    /**
     * Checks if system property {@link #CLOUD_SYSTEM_PROPERTY} is set.
     * @return true if property value is not null, not blank and not equal (ignoring case) to {@link #CLOUD_ALL}.
     */
    public static boolean cloudPropertySet() {
        String value = System.getProperty(CLOUD_SYSTEM_PROPERTY);
        if ( value != null && !value.isBlank() && !value.equalsIgnoreCase(CLOUD_ALL)) return true;
        return false;
    }

    /**
     * Return system property {@link #CLOUD_SYSTEM_PROPERTY}.
     * @return the property, whose value must be check by {@link #cloudPropertySet()}.
     */
    public static String cloudPropertyValue() {
        return System.getProperty(CLOUD_SYSTEM_PROPERTY);
    }
}
