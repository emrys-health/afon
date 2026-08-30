package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class DocumentFinderTest {

    private AfonPropertyProvider properties = new FilePropertyProvider("src/test/resources/config", List.of("test", "secret"));
    private DocumentFinder finder = new DocumentFinder(properties);

    @Test
    public void canFindDocumentsOnlyForAws() {
        canFindDocumentsIncludingAndExcluding("aws", new String[]{"azure", "gcp"});
    }

    @Test
    public void canFindDocumentsOnlyForAzure() {
        canFindDocumentsIncludingAndExcluding("azure", new String[]{"aws", "gcp"});
    }

    @Test
    public void canFindDocumentsOnlyForGcp() {
        canFindDocumentsIncludingAndExcluding("gcp", new String[]{"aws", "azure"});
    }

    private void canFindDocumentsIncludingAndExcluding(String include, String[] exclude) {
        System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, include);
        try {
            List<String> names = finder.findDocuments()
                    .stream().map(x -> x.getClass().getSimpleName()).collect(Collectors.toList());
            names.forEach(x -> assertThat(x).containsIgnoringCase(include));
            names.forEach(x -> assertThat(x).doesNotContainIgnoringCase(exclude));
            names.forEach(x -> assertThat(x).doesNotContain("Via", "With"));
        } finally {
            System.clearProperty(ConfigUtils.AFON_CLOUD_PROPERTY);
        }
    }


}