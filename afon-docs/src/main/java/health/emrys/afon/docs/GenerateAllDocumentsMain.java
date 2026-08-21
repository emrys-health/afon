package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.ConfigUtils.cloudPropertySet;
import static health.emrys.afon.config.ConfigUtils.cloudPropertyValue;
import static org.slf4j.LoggerFactory.getLogger;

public class GenerateAllDocumentsMain {
    private static final Logger logger = getLogger(GenerateAllDocumentsMain.class);
    private final AfonPropertyProvider provider;

    public GenerateAllDocumentsMain() {
        this.provider = new FilePropertyProvider();
    }

    private void generateDocuments() {
        Documenter documenter = new Documenter(provider);
        documenter.generateDocuments(documents());
    }

    private List<DocumentMetadata> documents() {
        List<DocumentMetadata> documents = new ArrayList<>();
        String packageName = "health.emrys.afon.docs";
        logger.info("Scanning classes in package {}", packageName);
        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                .acceptPackages(packageName)
                .scan()) {

            String filter = classNameFilter();
            scanResult.getAllClasses()
                    .filter(info ->
                            !info.isAbstract()
                                    && info.getSimpleName().endsWith("Document")
                                    && info.getSimpleName().matches(filter))
                    .forEach(info -> {
                        logger.info("Found class: {}", info.getSimpleName());
                        documents.add(document(info.loadClass()));
                    });
        }
        return documents;
    }

    private static @NonNull String classNameFilter() {
        String filter;
        if (cloudPropertySet()) {
            // for a given cloud, only consider the documents with the cloud in the name
            // but exclude the ViaAws
            filter = String.format("^(?!.*%s).*%s.*$", "ViaAws", capitalise(cloudPropertyValue()));
        } else {
            filter = ".*";
        }
        logger.info("Using class name filter: {}", filter);
        return filter;
    }

    private static @NonNull String capitalise(String cloud) {
        return cloud.substring(0, 1).toUpperCase() + cloud.substring(1);
    }

    private @NonNull DocumentMetadata document(Class<?> documentClass) {
        try {
            return (DocumentMetadata) documentClass.getConstructor(AfonPropertyProvider.class).newInstance(provider);
        } catch (Exception e) {
            logger.error("Failed to create document metadata {}: {}", documentClass.getName(), e.getCause(), e);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        new GenerateAllDocumentsMain().generateDocuments();
    }


}
