package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;

public class GenerateAllDocumentsMain {
    private final AfonPropertyProvider properties;

    public GenerateAllDocumentsMain() {
        this.properties = new FilePropertyProvider();
    }

    private void generateDocuments() {
        Documenter documenter = new Documenter(properties);
        DocumentFinder finder = new DocumentFinder(properties);
        documenter.generateDocuments(finder.findDocuments());
    }

    public static void main(String[] args) {
        new GenerateAllDocumentsMain().generateDocuments();
    }

}
