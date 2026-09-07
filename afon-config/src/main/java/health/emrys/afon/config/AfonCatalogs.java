package health.emrys.afon.config;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static health.emrys.afon.config.AfonProperty.AFON_CATALOGS_CSV;

/**
 * The currently configured {@link AfonCatalog}s, sourced from an {@link AfonPropertyProvider}: the
 * active catalog set is read from {@link AfonProperty#AFON_CATALOGS_CSV}, so it inherits that
 * property's usual file/env/system-property override and interpolation behaviour. Defaults to
 * {@link #DEFAULT_CATALOGS_CSV} (the full built-in catalog set) when unset.
 */
public class AfonCatalogs {

    public static final String DEFAULT_CATALOGS_CSV =
        "aws,aws_forbidden,aws_trino,azure,azure_external,azure_forbidden,gcp,gcp_external,gcp_forbidden";

    private final AfonPropertyProvider properties;

    public AfonCatalogs() {
        this(new FilePropertyProvider());
    }

    public AfonCatalogs(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public List<AfonCatalog> all() {
        String csv = properties.getValue(AFON_CATALOGS_CSV, DEFAULT_CATALOGS_CSV);
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
