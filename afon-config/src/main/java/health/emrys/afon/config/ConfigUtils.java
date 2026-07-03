package health.emrys.afon.config;

public class ConfigUtils {
    public static String protocolFor(String url) {
        if (url.toLowerCase().contains("localhost")) {
            return "http";
        }
        return "https";
    }
}
