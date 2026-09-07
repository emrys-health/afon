package health.emrys.afon.config;

import java.util.Objects;

/**
 * A catalog supported by Afon: a cloud plus an optional variant (forbidden/trino/external).
 * Instances are resolved through {@link AfonCatalogs} rather than constructed directly, so the
 * active set of catalogs is runtime-configurable.
 */
public final class AfonCatalog {

    public enum Variant {
        BASE, FORBIDDEN, TRINO, EXTERNAL
    }

    private final String name;
    private final AfonCloud cloud;
    private final Variant variant;

    private AfonCatalog(String name, AfonCloud cloud, Variant variant) {
        this.name = name;
        this.cloud = cloud;
        this.variant = variant;
    }

    /**
     * Parses a canonical catalog name (e.g. {@code "aws_forbidden"}) into an {@link AfonCatalog},
     * deriving its {@link AfonCloud} from the leading token and its {@link Variant} from the suffix.
     */
    public static AfonCatalog of(String canonicalName) {
        String name = canonicalName.toLowerCase();
        Variant variant = Variant.BASE;
        String cloudToken = name;
        for (Variant candidate : Variant.values()) {
            String suffix = "_" + candidate.name().toLowerCase();
            if (candidate != Variant.BASE && name.endsWith(suffix)) {
                variant = candidate;
                cloudToken = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        AfonCloud cloud = parseCloud(cloudToken, name);
        return new AfonCatalog(name, cloud, variant);
    }

    private static AfonCloud parseCloud(String cloudToken, String name) {
        for (AfonCloud candidate : AfonCloud.values()) {
            if (candidate.toString().equalsIgnoreCase(cloudToken)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Cannot determine cloud for Afon catalog '" + name + "'");
    }

    public AfonCloud cloud() {
        return cloud;
    }

    public Variant variant() {
        return variant;
    }

    /**
     * Matches the old {@code AfonCatalog} enum's {@code name()} (upper-case identifier).
     */
    public String name() {
        return name.toUpperCase();
    }

    /**
     * Matches the old {@code AfonCatalog} enum's {@code toString()} (canonical lower-case value).
     */
    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AfonCatalog other)) return false;
        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
