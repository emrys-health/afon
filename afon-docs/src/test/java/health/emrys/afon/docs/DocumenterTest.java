package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

class DocumenterTest {

    private final AfonPropertyProvider properties =
        new FilePropertyProvider("src/test/resources/config", List.of("test", "secret"));
    private final Documenter documenter = new Documenter(properties);

    @Test
    void canRedactSecrets() {
        String secret = properties.getValue(POLARIS_SECRET);
        DocumentMetadata document = new DocumentMetadata() {
            public String getDescription() {
                return "Log in with secret " + secret;
            }

            public String getFilename() {
                return "secret.md";
            }

            public String getFurtherNotes() {
                return "";
            }

            public List<Step> steps(StepOperations stepOperations) {
                return List.of();
            }
        };
        assertThat(documenter.getContent(document))
            .contains("Log in with secret <polaris_secret>")
            .doesNotContain(secret);
    }

    @Test
    void doesNotDocumentSecrets() {
        String content = documenter.getContent(new DeployAwsFullStackDocument(properties));
        assertThat(content).contains("afon-cli.sh manage-stack deploy aws-full");
        assertThat(content).doesNotContain(properties.getValue(POLARIS_SECRET));
    }

    @Test
    void documentsSnowflakeScenariosAsRunByManageStack() {
        // the scenarios read the catalogs, which the template config defines
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        Documenter documenter = new Documenter(properties);
        String content = documenter.getContent(new SnowflakeReadingAzureViaAwsDocument(properties));
        assertThat(content)
            .contains("afon-cli.sh manage-stack setup aws-snowflake")
            .contains("afon-cli.sh manage-stack run aws-snowflake reading-azure")
            .contains("afon-cli.sh manage-stack run aws-snowflake reading-azure ")
            .contains("snowflake_reads_catalog_azure")
            .doesNotContain("deploy-snowflake");
        assertThat(documenter.getContent(new SnowflakeWritingAzureViaAwsDocument(properties)))
            .contains("afon-cli.sh manage-stack run aws-snowflake writing-azure")
            .contains("snowflake_writes_to_azure");
    }

}
