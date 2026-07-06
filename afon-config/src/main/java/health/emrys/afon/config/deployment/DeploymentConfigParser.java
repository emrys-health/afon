package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Parses, validates and serialises {@code afon-deployment.yaml} files.
 * <p>
 * Validation happens in two layers:
 * <ol>
 *   <li><b>Schema</b> — the JSON Schema in {@code afon-deployment.schema.json},
 *       shared verbatim with the configuration UI so the two cannot drift.</li>
 *   <li><b>Semantic</b> — cross-reference rules the schema cannot express
 *       (unique cloud ids, dangling references, federation topology,
 *       secret-reference resolution).</li>
 * </ol>
 * All problems from both layers are collected into a single
 * {@link ConfigValidationException}.
 */
public final class DeploymentConfigParser {

    private static final Logger logger          = getLogger(DeploymentConfigParser.class);
    private static final String SCHEMA_RESOURCE = "/afon-deployment.schema.json";

    private final ObjectMapper yamlMapper;
    private final JsonSchema   schema;

    public DeploymentConfigParser() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                                             .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                                             .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                                             .build();
        yamlMapper = new ObjectMapper(yamlFactory)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        schema     = loadSchema();
    }

    private JsonSchema loadSchema() {
        try (InputStream in = DeploymentConfigParser.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found: " + SCHEMA_RESOURCE);
            }
            return JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + SCHEMA_RESOURCE, e);
        }
    }

    /**
     * Parse and validate, skipping secret-reference resolution (use the
     * overload with known property keys to enable it).
     */
    public AfonDeploymentConfig parse(Path configFile) throws IOException {
        return parse(Files.readString(configFile), Optional.empty());
    }

    /**
     * Parse and validate, additionally checking that every {@code *Ref} field
     * names a property in {@code knownPropertyKeys} (typically
     * {@code AfonProperty.getAllProperties()} or the keys of
     * {@code env.properties.example}).
     */
    public AfonDeploymentConfig parse(Path configFile, Set<String> knownPropertyKeys) throws IOException {
        return parse(Files.readString(configFile), Optional.of(knownPropertyKeys));
    }

    public AfonDeploymentConfig parse(String yamlContent, Optional<Set<String>> knownPropertyKeys) throws IOException {
        JsonNode     tree   = yamlMapper.readTree(yamlContent);
        List<String> errors = new ArrayList<>(schemaErrors(tree));
        if (!errors.isEmpty()) {
            // The document does not match the schema, so mapping to records may
            // fail or mislead — report the schema problems directly.
            throw new ConfigValidationException(errors);
        }
        AfonDeploymentConfig config = yamlMapper.treeToValue(tree, AfonDeploymentConfig.class);
        errors.addAll(semanticErrors(config, knownPropertyKeys));
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return config;
    }

    /**
     * Serialise a configuration back to human-readable YAML. Round-trips
     * losslessly with {@link #parse}.
     */
    public String toYaml(AfonDeploymentConfig config) {
        try {
            return yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialise deployment config", e);
        }
    }

    private List<String> schemaErrors(JsonNode tree) {
        Set<ValidationMessage> messages = schema.validate(tree);
        return messages.stream()
                       .map(ValidationMessage::getMessage)
                       .sorted()
                       .toList();
    }

    private List<String> semanticErrors(AfonDeploymentConfig config, Optional<Set<String>> knownPropertyKeys) {
        List<String> errors   = new ArrayList<>();
        Set<String>  cloudIds = new HashSet<>();

        for (CloudSpec cloud : config.clouds()) {
            if (!cloudIds.add(cloud.id())) {
                errors.add(format("clouds: duplicate cloud id '%s'", cloud.id()));
            }
        }

        Set<String> catalogNames = new HashSet<>();
        for (CatalogSpec catalog : config.catalogsOrEmpty()) {
            if (!catalogNames.add(catalog.name())) {
                errors.add(format("catalogs: duplicate catalog name '%s'", catalog.name()));
            }
            if (!cloudIds.contains(catalog.cloud())) {
                errors.add(format(
                    "catalogs[%s]: cloud '%s' is not a declared cloud id", catalog.name(), catalog.cloud()));
            }
            if (catalog.isFederated()) {
                if (!cloudIds.contains(catalog.via())) {
                    errors.add(format(
                        "catalogs[%s]: via '%s' is not a declared cloud id", catalog.name(), catalog.via()));
                } else if (catalog.via().equals(catalog.cloud())) {
                    errors.add(format(
                        "catalogs[%s]: a federated catalog must be proxied via a different cloud than '%s'",
                        catalog.cloud(), catalog.name()));
                }
            }
        }

        boolean hasSpark = false;
        for (EngineSpec engine : config.queryEnginesOrEmpty()) {
            if (EngineSpec.TYPE_SPARK.equals(engine.type())) {
                hasSpark = true;
            }
            if (engine.isSnowflake()) {
                checkRef(errors, knownPropertyKeys, "queryEngines[snowflake].jdbcUrlRef", engine.jdbcUrlRef());
                if (engine.readsCatalogs() != null) {
                    for (String name : engine.readsCatalogs()) {
                        if (!catalogNames.contains(name)) {
                            errors.add(format(
                                "queryEngines[snowflake]: readsCatalogs entry '%s' is not a declared catalog", name));
                        }
                    }
                }
            } else if (!cloudIds.contains(engine.target())) {
                errors.add(format(
                    "queryEngines[%s]: target '%s' is not a declared cloud id", engine.type(), engine.target()));
            }
        }

        WorkbenchSpec workbench = config.workbench();
        if (workbench != null) {
            if (!cloudIds.contains(workbench.target())) {
                errors.add(format(
                    "workbench: target '%s' is not a declared cloud id", workbench.target()));
            }
            AuthSpec auth = workbench.auth();
            if (auth != null) {
                checkRef(errors, knownPropertyKeys, "workbench.auth.clientIdRef", auth.clientIdRef());
                checkRef(errors, knownPropertyKeys, "workbench.auth.clientSecretRef", auth.clientSecretRef());
                checkRef(errors, knownPropertyKeys, "workbench.auth.cookieSecretRef", auth.cookieSecretRef());
            }
            if (WorkbenchSpec.TYPE_JUPYTER.equals(workbench.type()) && !hasSpark) {
                logger.warn("workbench is 'jupyter' but no spark engine is configured; "
                            + "the standard notebook templates use Spark Connect");
            }
        }

        return errors;
    }

    private static void checkRef(List<String> errors,
                                 Optional<Set<String>> knownPropertyKeys,
                                 String field,
                                 String ref) {
        if (ref == null || knownPropertyKeys.isEmpty()) {
            return;
        }
        if (!knownPropertyKeys.get().contains(ref)) {
            errors.add(format("%s: '%s' is not a known property key", field, ref));
        }
    }
}
