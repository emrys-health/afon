package health.emrys.afon.ops;

import static health.emrys.afon.config.ConfigUtils.*;
import static java.lang.String.format;

public class PrintEnvMain {

    private void printEnv() {
        String env = format(
                """
                
                Afon env:
                
                home dir: %s
                config dir: %s
                cdktf dir: %s
                cloud active: %s
                logging level: %s
                logging console level: %s
                """, afonHome(), configDir(), afonCkdtf(), cloud(), afonLoggingLevel(), afonLoggingConsoleLevel());
        System.out.println(env);
    }

    private String cloud() {
        return format(( cloudPropertySet() ? cloudPropertyValue() : "no afon.cloud property found"));
    }

    public static final void main(String[] args) {
        new PrintEnvMain().printEnv();
    }

}
