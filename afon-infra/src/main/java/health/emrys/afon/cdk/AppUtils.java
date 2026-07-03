package health.emrys.afon.cdk;

import java.util.Locale;

public class AppUtils {
    public static String toEnvironmentVariable(String propertyName) {
        return propertyName
            .replace('.', '_')
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
    }
}
