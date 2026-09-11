package health.emrys.afon.ops.config;

import health.emrys.afon.config.DefaultPropertyProvider;

import java.util.List;

public class OpsPropertyProvider extends DefaultPropertyProvider {

    public OpsPropertyProvider() {
        super(List.of(OpsDefaults.class));
    }

}
