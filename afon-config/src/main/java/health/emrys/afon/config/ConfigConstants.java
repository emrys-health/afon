package health.emrys.afon.config;

public class ConfigConstants {
    /**
     * System property selecting properties for a given cloud provider.
     */
    public static final String CLOUD_SYSTEM_PROPERTY = "afon.cloud";

    public static boolean cloudPropertySet() {
        String value = System.getProperty(CLOUD_SYSTEM_PROPERTY);
        if ( value != null && !value.isBlank() ) return true;
        return false;
    }

    public static String cloudPropertyValue() {
        return System.getProperty(CLOUD_SYSTEM_PROPERTY);
    }

}
