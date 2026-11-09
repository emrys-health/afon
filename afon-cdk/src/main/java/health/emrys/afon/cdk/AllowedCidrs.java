package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The allow-lists of source addresses of the components exposed over HTTPS that can be restricted, each configured per cloud as
 * {@code <CLOUD>_<COMPONENT>_ALLOWED_CIDRS}, eg {@code AWS_POLARIS_ALLOWED_CIDRS} or {@code AZURE_KEYCLOAK_ALLOWED_CIDRS}.
 * The other components, eg Jupyter, are open to all.
 */
public final class AllowedCidrs {

    /** The components that can be restricted, by their {@link HttpsRoute#component()}, and the property restricting them. */
    static final Map<String, AfonCloudProperty> PROPERTIES = Map.of(
        "polaris", AfonCloudProperty.POLARIS_ALLOWED_CIDRS,
        "keycloak", AfonCloudProperty.KEYCLOAK_ALLOWED_CIDRS);

    private AllowedCidrs() {
    }

    /**
     * @return the CIDRs that alone may reach the component on the cloud, as configured, or empty, ie open to all, if the
     * component cannot be restricted or its property is not set
     */
    public static List<String> of(AfonPropertyProvider properties, AfonCloud cloud, String component) {
        return Optional.ofNullable(PROPERTIES.get(component))
                       .map(property -> parse(properties.getCloudValue(cloud, property, "")))
                       .orElse(List.of());
    }

    /**
     * @return whether any component on the cloud is restricted, eg so that the ingress controller preserves the callers' IPs
     */
    public static boolean anyRestricted(AfonPropertyProvider properties, AfonCloud cloud) {
        return PROPERTIES.keySet().stream().anyMatch(component -> !of(properties, cloud, component).isEmpty());
    }

    /**
     * Parses a comma-separated list of IP addresses or CIDRs, ignoring blanks, an address being made the CIDR of that address
     * alone, eg {@code 203.0.113.7} becomes {@code 203.0.113.7/32}, as the AWS ALB only takes CIDRs.
     */
    public static List<String> parse(String csv) {
        return Arrays.stream(csv.split(","))
                     .map(String::trim)
                     .filter(cidr -> !cidr.isEmpty())
                     .map(AllowedCidrs::asCidr)
                     .distinct()
                     .toList();
    }

    private static String asCidr(String addressOrCidr) {
        if (addressOrCidr.contains("/")) {
            return addressOrCidr;
        }
        return addressOrCidr + (addressOrCidr.contains(":") ? "/128" : "/32");
    }
}
