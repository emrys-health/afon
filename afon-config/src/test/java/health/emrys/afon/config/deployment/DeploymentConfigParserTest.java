package health.emrys.afon.config.deployment;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DeploymentConfigParserTest {

    private static final Path EXAMPLES_DIR = Path.of("..", "docs", "examples");

    private final DeploymentConfigParser parser = new DeploymentConfigParser();

    @BeforeAll
    public static void initializeConstants() throws ClassNotFoundException {
        Class.forName(AfonProperties.class.getName());
    }

    @Test
    public void everyExampleValidatesAndRoundTripsLosslessly() throws IOException {
        List<Path> examples = exampleFiles();
        assertThat(examples).isNotEmpty();
        for (Path example : examples) {
            AfonDeploymentConfig parsed = parser.parse(example, AfonProperty.getAllProperties());
            String               yaml   = parser.toYaml(parsed);
            AfonDeploymentConfig reparsed = parser.parse(yaml, Optional.empty());
            assertThat(reparsed).as("round trip of %s", example).isEqualTo(parsed);
        }
    }

    @Test
    public void emrysDemoExampleMatchesExpectedTopology() throws IOException {
        AfonDeploymentConfig config = parser.parse(
            EXAMPLES_DIR.resolve("emrys-demo.yaml"), AfonProperty.getAllProperties());
        assertThat(config.clouds()).extracting(CloudSpec::id).containsExactly("aws", "azure", "gcp");
        assertThat(config.catalogsOrEmpty()).hasSize(9);
        assertThat(config.queryEnginesOrEmpty()).extracting(EngineSpec::type)
                                                .containsExactly("spark", "trino", "snowflake");
        assertThat(config.workbench().type()).isEqualTo(WorkbenchSpec.TYPE_JUPYTER);
    }

    @Test
    public void rejectsUnknownApiVersion() {
        String yaml = minimalYaml().replace("apiVersion: afon/v1", "apiVersion: afon/v2");
        assertValidationFails(yaml, "afon/v1");
    }

    @Test
    public void rejectsDuplicateCloudIds() {
        String yaml = minimalYaml() + """
          - id: aws-primary
            provider: aws
            region: eu-west-1
            storage:
              bucket: another-bucket
            polaris:
              domain: other.example.com
        """.stripTrailing();
        assertValidationFails(yaml, "duplicate cloud id 'aws-primary'");
    }

    @Test
    public void rejectsDanglingEngineTarget() {
        String yaml = minimalYaml() + """
        queryEngines:
          - type: spark
            target: no-such-cloud
        """.stripTrailing();
        assertValidationFails(yaml, "'no-such-cloud' is not a declared cloud id");
    }

    @Test
    public void rejectsFederatedCatalogProxiedViaItsOwnCloud() {
        String yaml = minimalYaml() + """
        catalogs:
          - name: self_federated
            cloud: aws-primary
            type: federated
            via: aws-primary
        """.stripTrailing();
        assertValidationFails(yaml, "must be proxied via a different cloud");
    }

    @Test
    public void rejectsInternalCatalogWithVia() {
        String yaml = minimalYaml() + """
        catalogs:
          - name: main
            cloud: aws-primary
            type: internal
            via: aws-primary
        """.stripTrailing();
        assertThatThrownBy(() -> parser.parse(yaml, Optional.empty()))
            .isInstanceOf(ConfigValidationException.class);
    }

    @Test
    public void rejectsSnowflakeWithTargetOrWithoutJdbcUrlRef() {
        String withTarget = minimalYaml() + """
        queryEngines:
          - type: snowflake
            target: aws-primary
            jdbcUrlRef: SNOWFLAKE_JDBC_URL
        """.stripTrailing();
        assertThatThrownBy(() -> parser.parse(withTarget, Optional.empty()))
            .isInstanceOf(ConfigValidationException.class);

        String withoutRef = minimalYaml() + """
        queryEngines:
          - type: snowflake
        """.stripTrailing();
        assertThatThrownBy(() -> parser.parse(withoutRef, Optional.empty()))
            .isInstanceOf(ConfigValidationException.class);
    }

    @Test
    public void rejectsUnknownSecretReference() throws IOException {
        String yaml = minimalYaml() + """
        queryEngines:
          - type: snowflake
            jdbcUrlRef: NO_SUCH_PROPERTY
        """.stripTrailing();
        assertThatThrownBy(() -> parser.parse(yaml, Optional.of(AfonProperty.getAllProperties())))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("NO_SUCH_PROPERTY");
        // ...but the same config passes when reference checking is off:
        AfonDeploymentConfig config = parser.parse(yaml, Optional.empty());
        assertThat(config.queryEnginesOrEmpty()).hasSize(1);
    }

    @Test
    public void rejectsUnknownFields() {
        String yaml = minimalYaml() + """
        somethingUnexpected: true
        """.stripTrailing();
        assertThatThrownBy(() -> parser.parse(yaml, Optional.empty()))
            .isInstanceOf(ConfigValidationException.class);
    }

    private void assertValidationFails(String yaml, String expectedMessageFragment) {
        assertThatThrownBy(() -> parser.parse(yaml, Optional.empty()))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining(expectedMessageFragment);
    }

    private static String minimalYaml() {
        return """
        apiVersion: afon/v1
        deployment:
          name: test
        clouds:
          - id: aws-primary
            provider: aws
            region: eu-west-2
            storage:
              bucket: some-bucket
            polaris:
              domain: polaris.example.com
        """;
    }

    private static List<Path> exampleFiles() throws IOException {
        try (Stream<Path> files = Files.list(EXAMPLES_DIR)) {
            return files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
    }
}
