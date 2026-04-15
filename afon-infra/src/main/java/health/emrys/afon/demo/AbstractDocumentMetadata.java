package health.emrys.afon.demo;

import org.slf4j.Logger;

import static health.emrys.afon.demo.StepDocumentation.mvnArgumentsToRun;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public abstract class AbstractDocumentMetadata implements DocumentMetadata {

    private static final Logger log = getLogger(AbstractDocumentMetadata.class);

    public String getDescription() {
        String mvnDeploy = "";
        try {
            Class<?> clazz = getDeployHomologue();
            mvnDeploy = format("""
                To run the actual deployment, run:
                ```shell
                mvn %s -Pdemo
                ```
                """, mvnArgumentsToRun(clazz, null, "exec"));
        } catch (ClassNotFoundException e) {
            log.warn("Cannot find the deployment class for " + this.getClass().getSimpleName(), e);
        }
        return format("""
            # Introduction
            
            To create this documentation, run:
            ```
            mvn %s
            ```
            %s
            
            %s
            """, mvnArgumentsToRun(this.getClass(), null, "java"),
                             mvnDeploy,
                             getSupplementaryIntro());
    }

    public Class<?> getDeployHomologue() throws ClassNotFoundException {
        String name = this.getClass().getName();
        return Class.forName(name.replace("Document", "Deploy"));
    }

    abstract public String getSupplementaryIntro();

    public String getFurtherNotes() {
        return "";
    }
}
