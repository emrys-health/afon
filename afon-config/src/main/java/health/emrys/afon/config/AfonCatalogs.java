package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * The configured set of {@link AfonCatalog}s, assembled per cloud from its
 * {@code <CLOUD>_CATALOG_IDS} property (eg {@code AWS_CATALOG_IDS}) and, for
 * each id in that list, the matching {@code <CLOUD>_CATALOG_NAME_<id>},
 * {@code <CLOUD>_CATALOG_TYPE_<id>}, {@code <CLOUD>_BUCKET_NAME_<id>} and
 * {@code <CLOUD>_ADMIN_GRANTS_<id>} properties - conventionally defined in
 * {@code catalogs-<cloud>.properties}. A cloud with no {@code <CLOUD>_CATALOG_IDS} configured
 * (eg because that cloud's properties weren't loaded for a cloud-scoped run) simply contributes
 * no catalogs.
 */
public class AfonCatalogs {

    /**
     * Where a catalog is served from, which a config says in {@code <CLOUD>_CATALOG_SOURCE_<id>}, by the {@link #value()} of the source: when it says none, {@link #STACK}.
     */
    public enum Source {
        /** The stack's own Polaris, which makes the catalog, and whose storage is the stack's: the default, and all there is outside a project. */
        STACK("stack"),
        /**
         * The Polaris of a Data Provider that a TRE project reads, which the stack neither makes nor holds the storage of.  A project's {@code configure} writes
         * them from its {@code dataProviders}, with {@code <CLOUD>_CATALOG_URI_<id>}, the endpoint of the Iceberg REST catalog, and
         * {@code <CLOUD>_CATALOG_AUDIENCE_<id>}, that of the tokens for it, and the cloud of the properties is that of the storage, which is not always the TRE's.
         * The scripts that make and delete the storage of a stack's catalogs skip them, by this value.
         */
        DATA_PROVIDER("data-provider");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        /** @return the value in the config, eg {@code data-provider}, which the scripts read too */
        public String value() {
            return value;
        }

        /**
         * @return the source whose value in the config is the one given, {@link #STACK} if it is blank
         * @throws IllegalArgumentException if it is not the value of a source
         */
        public static Source of(String value) {
            String trimmed = value == null ? "" : value.trim();
            return Arrays.stream(values()).filter(s -> s.value.equals(trimmed)).findFirst()
                         .orElseGet(() -> {
                             if (trimmed.isEmpty()) {
                                 return STACK;
                             }
                             throw new IllegalArgumentException("'" + value + "' is not the source of a catalog: " + Arrays.stream(values()).map(Source::value).toList());
                         });
        }
    }

    private final List<AfonCatalog> allCatalogs;
    private final List<AfonCatalog> stackCatalogs;

    public AfonCatalogs(AfonPropertyProvider properties) {
        this.allCatalogs = createCatalogs(properties);
        this.stackCatalogs = bySource(Source.STACK);
    }

    /**
     * @return the catalogs of the stack's own Polaris, {@link Source#STACK}: what it makes and grants, what its scripts make the storage of, and what its
     * engines query, which is all the catalogs there are outside a project.  The others are {@link #bySource}.
     */
    public List<AfonCatalog> all() {
        return stackCatalogs;
    }

    /**
     * @param source where the catalogs are served from
     * @return the catalogs from the source, none from {@link Source#DATA_PROVIDER} outside a project
     */
    public List<AfonCatalog> bySource(Source source) {
        return allCatalogs.stream().filter(c -> c.source() == source).toList();
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
        String csv = raw.getProperty(idsProperty(cloud));
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
        Source source;
        try {
            source = Source.of(raw.getProperty(prefix + "_CATALOG_SOURCE_" + id));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Catalog '" + id + "' has " + prefix + "_CATALOG_SOURCE_" + id + ": " + e.getMessage(), e);
        }
        return switch (source) {
            case STACK -> new AfonCatalog(id, cloud, name, type, bucketName, adminGrants);
            case DATA_PROVIDER -> new AfonCatalog(id, cloud, name, type, bucketName, adminGrants, Source.DATA_PROVIDER,
                                                  required(raw, prefix + "_CATALOG_URI_" + id, id), required(raw, prefix + "_CATALOG_AUDIENCE_" + id, id));
        };
    }

    private static String required(Properties raw, String property, String id) {
        String value = raw.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Catalog '" + id + "' is missing " + property);
        }
        return value.trim();
    }

    private static String idsProperty(AfonCloud cloud) {
        return prefix(cloud) + "_CATALOG_IDS";
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

    /**
     * @param types catalog types (see {@link AfonCatalog#type()}), matched exactly.
     * @return the catalogs whose type is one of {@code types}.
     */
    public List<AfonCatalog> allIncluding(String... types) {
        Set<String> included = Set.copyOf(Arrays.asList(types));
        return all().stream().filter(c -> included.contains(c.type())).toList();
    }

    /**
     * @param types catalog types (see {@link AfonCatalog#type()}), matched exactly.
     * @return the catalogs whose type is none of {@code types}.
     */
    public List<AfonCatalog> allExcluding(String... types) {
        Set<String> excluded = Set.copyOf(Arrays.asList(types));
        return all().stream().filter(c -> !excluded.contains(c.type())).toList();
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
