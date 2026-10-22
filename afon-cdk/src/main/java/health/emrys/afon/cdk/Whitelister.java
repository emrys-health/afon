package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;

public class Whitelister {

    private final AfonPropertyProvider properties;

    public Whitelister(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public String polarisWhitelistCidrCSV(AfonCloud cloud) {
        return properties.getCloudValue(cloud, AfonCloudProperty.POLARIS_ALLOWED_CIDRS, "");
    }

    public List<String> polarisWhitelistCidr(AfonCloud cloud) {
        String csv = polarisWhitelistCidrCSV(cloud);
        return parseCidrsIn(csv);
    }

    static @NonNull List<String> parseCidrsIn(String csv) {
        return Arrays.stream(
                         csv
                             .split(","))
                     .map(String::trim)
                     .filter(cidr -> !cidr.isEmpty())
                     .toList();
    }

}
