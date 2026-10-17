package health.emrys.afon.ops.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;

public class GenerateAllDocumentsMain {
    private final AfonPropertyProvider properties;
    private final AfonUserProvider users;

    public GenerateAllDocumentsMain() {
        this.properties = new OpsPropertyProvider();
        this.users = new FileUserProvider();
    }

    private void generateDocuments() {
        Documenter documenter = new Documenter(properties, users);
        DocumentFinder finder = new DocumentFinder(properties, users);
        documenter.generateDocuments(finder.findDocuments());
    }

    public static void main(String[] args) {
        new GenerateAllDocumentsMain().generateDocuments();
    }

}
