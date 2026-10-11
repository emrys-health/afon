package health.emrys.afon.ops.docs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFinderTest {

    private AfonPropertyProvider properties = new FilePropertyProvider("src/test/resources/config", List.of("test", "secret"));
    private DocumentFinder finder = new DocumentFinder(properties);

    @Test
    void canFindDocumentsOnlyForAws() {
        canFindDocumentsOf("aws");
    }

    @Test
    void canFindDocumentsOnlyForAzure() {
        canFindDocumentsOf("azure");
    }

    @Test
    void canFindDocumentsOnlyForGcp() {
        canFindDocumentsOf("gcp");
    }

    private void canFindDocumentsOf(String include) {
        System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, include);
        try {
            List<DocumentMetadata> documents = finder.findDocuments();
            assertThat(documents).isNotEmpty();
            // the documents spanning clouds are excluded
            documents.forEach(x -> assertThat(x.clouds()).as(x.getFilename()).containsExactly(AfonCloud.valueOf(include.toUpperCase())));
            assertThat(documents.stream().map(DocumentMetadata::getFilename)).contains("setup_" + include + "_polaris_stack.md");
        } finally {
            System.clearProperty(ConfigUtils.AFON_CLOUD_PROPERTY);
        }
    }


}