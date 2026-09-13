package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

import java.util.*;

import static health.emrys.afon.config.AfonProperty.CATALOG_NAMES_CSV;

/**
 * The configured set of {@link AfonCatalog}s, created from {@link AfonProperty#CATALOG_NAMES_CSV}.
 * Defaults to {@link #DEFAULT_CATALOG_NAMES_CSV} (the full built-in catalog set) when unset.
 */
public class AfonCatalogs {

    public static final String DEFAULT_CATALOG_NAMES_CSV =
        "aws,aws_forbidden,aws_trino,azure,azure_external,azure_forbidden,gcp,gcp_external,gcp_forbidden";

    private final AfonPropertyProvider properties;
    private final List<AfonCatalog> catalogs;

    public AfonCatalogs(AfonPropertyProvider properties) {
        this.properties = properties;
        this.catalogs = createCatalogs();
    }

    public List<AfonCatalog> all() {
        return catalogs;
    }

    private @NonNull List<AfonCatalog> createCatalogs() {
        String csv = properties.getValue(CATALOG_NAMES_CSV, DEFAULT_CATALOG_NAMES_CSV);
        if (csv.isBlank()) csv = DEFAULT_CATALOG_NAMES_CSV;
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(AfonCatalog::of)
                .toList();
    }

    /**
     * @param name a catalog's canonical name (case-insensitive), e.g. {@code "aws_forbidden"}.
     * @throws NoSuchElementException if no active catalog matches {@code name}.
     */
    public AfonCatalog get(String name) {
        return all().stream()
                     .filter(c -> c.toString().equalsIgnoreCase(name))
                     .findFirst()
                     .orElseThrow(() -> new NoSuchElementException("Unknown Afon catalog: " + name));
    }

    public List<AfonCatalog> byCloud(AfonCloud cloud) {
        return all().stream().filter(c -> c.cloud() == cloud).toList();
    }

    public List<AfonCatalog> base() {
        return all().stream().filter(c -> c.variant() == AfonCatalog.Variant.BASE).toList();
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
