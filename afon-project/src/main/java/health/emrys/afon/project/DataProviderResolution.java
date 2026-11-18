package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.ConfigUtils.configDir;

/**
 * The catalog of a Data Provider that a TRE project's file names, {@code {project: lon, catalog: cvd}}, read from the Data Provider's own project, a
 * project with the role {@link ProjectRole#DATA} of the same Afon home, as the properties of its config would give it: its Polaris is at its domain, the
 * audience of the tokens for it is that of its identity, and the storage of the catalog is the project's own.  What the Data Provider's file and the config
 * it is made from say once, so the TRE need not say it again, and cannot say it wrong.
 */
final class DataProviderResolution {

    /** Where a Polaris serves its Iceberg REST catalog. */
    static final String CATALOG_PATH = "/api/catalog";

    private DataProviderResolution() {
    }

    /**
     * @param afonHome  the Afon home, whose projects the Data Provider is one of
     * @param project   the id of the Data Provider's project
     * @param catalog   the id of the catalog of the Data Provider
     * @param id        the id that the catalog has in the TRE project
     * @param issuerUrl the issuer of the identity that the TRE project uses
     * @param path      the key of the TRE's file that names the Data Provider, for the problems
     * @param problems  where a problem is noted
     * @return the catalog as the TRE project reads it, or {@code null}, noting a problem, if it cannot be
     */
    static TreProject.@Nullable DataProvider resolve(@NonNull Path afonHome, @NonNull String project, @NonNull String catalog, @NonNull String id,
                                                 @Nullable String issuerUrl, @NonNull String path, @NonNull List<String> problems) {
        try {
            FileProjectProvider file = FileProjectProvider.forProject(afonHome, project);
            if (!file.exists()) {
                problems.add(String.format("%s.project names %s, which has no file %s: make it first, as the project that deploys the Data Provider",
                                           path, project, file.file()));
                return null;
            }
            if (!(file.getProject() instanceof DataProject data)) {
                problems.add(String.format("%s.project names %s, which has the role %s, not data", path, project, file.getProject().role()));
                return null;
            }
            if (!data.catalogs().contains(catalog)) {
                problems.add(String.format("%s.catalog '%s' is not a catalog of %s, which has %s", path, catalog, project, data.catalogs()));
                return null;
            }
            if (issuerUrl != null && !issuerUrl.equals(data.identity().issuerUrl())) {
                problems.add(String.format("%s: %s trusts the identity at %s, and this project uses the one at %s: a Data Provider reads the catalogs of projects "
                                           + "of its own identity", path, project, data.identity().issuerUrl(), issuerUrl));
                return null;
            }
            return provider(afonHome, data, catalog, id);
        } catch (ProjectException e) {
            e.problems().forEach(problem -> problems.add(String.format("%s.project %s: %s", path, project, problem)));
            return null;
        } catch (IllegalArgumentException | IllegalStateException | java.io.UncheckedIOException e) {
            problems.add(String.format("%s.project %s: %s", path, project, e.getMessage()));
            return null;
        }
    }

    private static TreProject.DataProvider provider(Path afonHome, DataProject data, String catalog, String id) {
        Path envConfig = data.env() != null ? ProjectEnv.envConfigDir(afonHome, data.env()) : Path.of(configDir());
        Properties env = ProjectEnv.envProperties(envConfig);
        Map<String, Map<String, String>> derived = ProjectEnv.derive(data, null, null, env);
        AfonCloud cloud = data.cloud();
        String prefix = cloud.name() + "_";
        String domain = derived.getOrDefault("afon-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", Map.of()).get(prefix + "POLARIS_DOMAIN_NAME");
        if (domain == null || domain.isBlank()) {
            domain = env.getProperty(prefix + "POLARIS_DOMAIN_NAME", "").trim();
        }
        if (domain.isEmpty()) {
            throw new IllegalStateException("its Polaris has no domain: " + prefix + "POLARIS_DOMAIN_NAME is not set in the config that it is made from, and "
                                            + "its file gives none in stacks.data.domain");
        }
        // what the TRE's whitelist allows is the storage of the catalog: the account on Azure, whose containers are the catalogs', else the bucket
        String bucket = cloud == AfonCloud.AZURE
            ? derived.getOrDefault("afon-azure.properties", Map.of()).get("AZURE_STORAGE_ACCOUNT")
            : derived.getOrDefault("catalogs-" + cloud.toString().toLowerCase(Locale.ROOT) + ".properties", Map.of()).get(prefix + "BUCKET_NAME_" + catalog);
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("cannot name the storage of its catalog " + catalog);
        }
        return new TreProject.DataProvider(id, "https://" + domain + CATALOG_PATH, catalog, data.identity().audience(), bucket, cloud, data.id().value());
    }
}
