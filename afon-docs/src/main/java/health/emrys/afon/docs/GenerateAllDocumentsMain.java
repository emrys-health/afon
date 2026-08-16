package health.emrys.afon.docs;

import health.emrys.afon.config.FilePropertyProvider;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.slf4j.LoggerFactory.getLogger;

public class GenerateAllDocumentsMain {
    private static final Logger logger = getLogger(GenerateAllDocumentsMain.class);
    private final Properties properties;
    private final Properties secretProperties;

    public GenerateAllDocumentsMain() {
        FilePropertyProvider provider = new FilePropertyProvider();
        this.properties = provider.getProperties();
        this.secretProperties = provider.getSecretProperties();
    }

    private void generateDocuments() {
        Documenter documenter = new Documenter(secretProperties);
        documenter.generateDocuments(documents());
    }

    private List<DocumentMetadata> documents() {
        List<DocumentMetadata> documents = new ArrayList<>();
        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                .acceptPackages("health.emrys.afon.docs")
                .scan()) {

            scanResult.getAllClasses()
                    .filter(info ->
                            info.getSimpleName().endsWith("Document")
                                    && !info.isAbstract()
                    )
                    .forEach(info -> {
                        documents.add(document(info.loadClass()));
                    });
        }
        return documents;
    }

    private @NonNull DocumentMetadata document(Class<?> documentClass) {
        try {
            return (DocumentMetadata) documentClass.getConstructor(Properties.class).newInstance(properties);
        } catch (Exception e) {
            logger.error("Failed to create document metadata {}: {}", documentClass.getName(), e.getCause(), e);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        new GenerateAllDocumentsMain().generateDocuments();
    }


}
