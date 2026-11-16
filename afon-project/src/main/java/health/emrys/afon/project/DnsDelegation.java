package health.emrys.afon.project;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How the DNS zones of a project's domains are delegated from the zone of their parent domain, which must be done before the stack can get its
 * certificates, see {@code docs/dns_config.md}.
 */
public enum DnsDelegation {

    /** The deployer adds the {@code NS} records that {@code dns-delegate} shows to the parent zone, wherever it is: the default. */
    MANUAL,

    /** {@code up} writes them, as {@code dns-delegate --route53} does, to the Route 53 hosted zone of the parent domain in the current AWS account. */
    ROUTE53;

    /**
     * @return the name in {@code project.yaml}, in lower case
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the delegation of the name in {@code project.yaml}, or empty if it is not one
     */
    public static Optional<DnsDelegation> of(String value) {
        return Arrays.stream(values()).filter(delegation -> delegation.value().equals(value)).findFirst();
    }
}
