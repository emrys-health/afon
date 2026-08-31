package health.emrys.afon.ops;

import health.emrys.afon.config.AfonEnvPrinter;
import health.emrys.afon.config.DefaultPropertyProvider;

public class ShowEnvMain {

    public static final void main(String[] args) {
        var properties = new DefaultPropertyProvider();
        new AfonEnvPrinter(properties).printEnv(System.out);
    }

}
