package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * The configured set of {@link AfonCatalog}s, assembled per cloud from its
 * {@code <CLOUD>_CATALOG_NAMES} property (eg {@link AfonProperty#AWS_CATALOG_NAMES}) and, for
 * each id in that list, the matching {@code <CLOUD>_CATALOG_NAME_<id>},
 * {@code <CLOUD>_CATALOG_TYPE_<id>}, {@code <CLOUD>_BUCKET_NAME_<id>} and
 * {@code <CLOUD>_ADMIN_GRANTS_<id>} properties - conventionally defined in
 * {@code catalogs-<cloud>.properties}. A cloud with no {@code <CLOUD>_CATALOG_NAMES} configured
 * (eg because that cloud's properties weren't loaded for a cloud-scoped run) simply contributes
 * no catalogs.
 */
public class AfonCatalogs {

    private final List<AfonCatalog> catalogs;

    public AfonCatalogs(AfonPropertyProvider properties) {
        this.catalogs = createCatalogs(properties);
    }

    public List<AfonCatalog> all() {
        return catalogs;
    }

    private static @NonNull List<AfonCatalog> createCatalogs(AfonPropertyProvider properties) {
        Properties raw = properties.getProperties();
        List<AfonCatalog> catalogs = new ArrayList<>();
        for (AfonCloud cloud : AfonCloud.values()) {
            catalogs.addAll(catalogsFor(cloud, raw));
        }
        return List.copyOf(catalogs);
    }

    private static List<AfonCatalog> catalogsFor(AfonCloud cloud, Properties raw) {
        String csv = raw.getProperty(namesProperty(cloud));
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> catalogOf(cloud, id, raw))
                .toList();
    }

    private static AfonCatalog catalogOf(AfonCloud cloud, String id, Properties raw) {
        String prefix = prefix(cloud);
        String name = raw.getProperty(prefix + "_CATALOG_NAME_" + id, id);
        String type = raw.getProperty(prefix + "_CATALOG_TYPE_" + id);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Catalog '" + id + "' is missing " + prefix + "_CATALOG_TYPE_" + id);
        }
        String bucketName = raw.getProperty(prefix + "_BUCKET_NAME_" + id);
        boolean adminGrants = Boolean.parseBoolean(raw.getProperty(prefix + "_ADMIN_GRANTS_" + id, "true"));
        return new AfonCatalog(id, cloud, name, type, bucketName, adminGrants);
    }

    private static String namesProperty(AfonCloud cloud) {
        return prefix(cloud) + "_CATALOG_NAMES";
    }

    private static String prefix(AfonCloud cloud) {
        return cloud.toString().toUpperCase();
    }

    /**
     * @param id a catalog's id (case-insensitive), e.g. {@code "aws_forbidden"}.
     * @throws NoSuchElementException if no active catalog matches {@code id}.
     */
    public AfonCatalog get(String id) {
        return all().stream()
                     .filter(c -> c.id().equalsIgnoreCase(id))
                     .findFirst()
                     .orElseThrow(() -> new NoSuchElementException("Unknown Afon catalog: " + id));
    }

    public List<AfonCatalog> byCloud(AfonCloud cloud) {
        return all().stream().filter(c -> c.cloud() == cloud).toList();
    }

    /**
     * The "primary" catalog per cloud - the one whose id is the cloud's own name (eg {@code "aws"}).
     */
    public List<AfonCatalog> base() {
        return all().stream().filter(c -> c.id().equalsIgnoreCase(c.cloud().toString())).toList();
    }

    public AfonCatalog aws() {
        return get("aws");
    }

    public AfonCatalog awsForbidden() {
        return get("aws_forbidden");
    }

    public AfonCatalog awsTrino() {
        return get("aws_trino");
    }

    public AfonCatalog azure() {
        return get("azure");
    }

    public AfonCatalog azureExternal() {
        return get("azure_external");
    }

    public AfonCatalog azureForbidden() {
        return get("azure_forbidden");
    }

    public AfonCatalog gcp() {
        return get("gcp");
    }

    public AfonCatalog gcpExternal() {
        return get("gcp_external");
    }

    public AfonCatalog gcpForbidden() {
        return get("gcp_forbidden");
    }
}
