package health.emrys.afon.config;

import java.util.Objects;

/**
 * A catalog supported by Afon: an id (used to look it up and to key its per-catalog
 * configuration, eg {@code "aws_forbidden"}), the cloud it belongs to, its actual Polaris-facing
 * name (usually the same as its id, but can diverge - eg for a Glue-backed catalog whose name is
 * a pre-existing Glue Data Catalog name), a type that determines which Java code builds it
 * ({@code "internal"}/{@code "external"} are generic; anything else is an explicitly registered
 * custom factory), and the bucket name / admin-grants flag the generic types need.
 * Instances are resolved through {@link AfonCatalogs} rather than constructed directly, so the
 * active set of catalogs is entirely configuration-driven.
 */
public final class AfonCatalog {

    private final String id;
    private final AfonCloud cloud;
    private final String name;
    private final String type;
    private final String bucketName;
    private final boolean adminGrants;

    public static AfonCatalog catalogFor(AfonCloud cloud, AfonCatalogs catalogs) {
        switch (cloud) {
            case AWS:
                return catalogs.aws();
            case AZURE:
                return catalogs.azure();
            case GCP:
                return catalogs.gcp();
            default:
                throw new IllegalArgumentException("Unknown cloud: " + cloud);
        }
    }

    AfonCatalog(String id, AfonCloud cloud, String name, String type, String bucketName, boolean adminGrants) {
        this.id = id;
        this.cloud = cloud;
        this.name = name;
        this.type = type;
        this.bucketName = bucketName;
        this.adminGrants = adminGrants;
    }

    /**
     * Builds an ad hoc {@link AfonCatalog} not sourced from {@link AfonCatalogs} - eg for a test
     * that needs a one-off catalog outside the configured set. Its name equals its id.
     */
    public static AfonCatalog adHoc(String id, AfonCloud cloud, String type, String bucketName, boolean adminGrants) {
        return new AfonCatalog(id, cloud, id, type, bucketName, adminGrants);
    }

    /**
     * The identifier used to look this catalog up (see {@link AfonCatalogs#get}) and to key its
     * {@code <CLOUD>_..._<id>} configuration, eg {@code "aws_forbidden"}.
     */
    public String id() {
        return id;
    }

    public AfonCloud cloud() {
        return cloud;
    }

    /**
     * The type that determines which Java code builds this catalog: {@code "internal"} and
     * {@code "external"} are built generically from {@link #bucketName()}/{@link #adminGrants()};
     * any other value must be registered as an explicit custom factory.
     */
    public String type() {
        return type;
    }

    public String bucketName() {
        return bucketName;
    }

    /**
     * Whether admin grants are allowed on this catalog. Defaults to {@code true} when unset.
     */
    public boolean adminGrants() {
        return adminGrants;
    }

    /**
     * The catalog's actual Polaris-facing name. Usually equal to {@link #id()}, but can diverge.
     */
    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AfonCatalog other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
