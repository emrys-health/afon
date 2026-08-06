package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

public class ConfigUtils {
    /**
     *  Returns the dir specified by system property ${afon.home}.
     *  If not found it defauls to $HOME/.afon
     * @return the afon home dir
     */
    public static @NonNull String afonHome() {
        return System.getProperty("afon.home", System.getenv("HOME")+"/.afon");
    }

    /**
     * Returns the config dir located in {@link #afonHome()}/config.
     * @return the config dir
     */
    public static @NonNull String configDir() {
        return  afonHome() + "/config";
    }
}
