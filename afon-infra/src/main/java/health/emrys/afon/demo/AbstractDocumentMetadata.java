package health.emrys.afon.demo;

import org.slf4j.Logger;

import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static health.emrys.afon.demo.StepDocumentation.mvnArgumentsToRun;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public abstract class AbstractDocumentMetadata implements DocumentMetadata {

    private static final Logger log = getLogger(AbstractDocumentMetadata.class);

    public String getDescription() {
        String mvnDeploy = "";
        String stepInstructions = "";
        try {
            Class<?> clazz = getDeployHomologue();
            mvnDeploy = format("""
                
                To run the actual deployment, run:
                ```shell
                mvn %s -Pdemo
                ```
                """, mvnArgumentsToRun(clazz, null, "exec"));
            Object o = clazz.getConstructor(new Class[]{}).newInstance(new Object[]{});
            if (o instanceof StepsProvider) {
                StepOperations dummyOps     = new StepOperations(new StepDocumentation(OutputStream.nullOutputStream()));
                List<Step>     steps        = ((StepsProvider) o).stepsUsing(dummyOps);
                String[]       stepArray    = steps.stream().map(x -> x.name).toArray(String[]::new);
                if (stepArray.length > 0) {
                    stepInstructions = format("""
                    
                    The steps to run are:
                    1. %s
                    
                    If the job fails on a given step, you can add this switch to the mvn command above:
                    
                    `-Dexec.args="STEP_FROM_LIST_ABOVE"`
                    
                    and the steps will continue starting at that one and the goal should be `exec:exec` not `exec:java`.
                    
                    For example:
                    
                    `mvn compile -Pdemo -Dexec.mainClass="health.emrys.afon.demo.DeployExternalCatalogsMain"  -Dexec.args="az_external_aws_check"  exec:exec`
                    """, String.join(("\n1. "), stepArray));
                }
            }
        } catch (ClassNotFoundException e) {
            log.warn("Cannot find the deployment class for " + this.getClass().getSimpleName(), e);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            log.warn("Could not find the steps for the deploy class", e);
        }
        return format("""
            # Introduction
            
            To create this documentation, run:
            ```
            mvn %s
            ```
            %s
            %s
            %s
            """, mvnArgumentsToRun(this.getClass(), null, "java"),
                             mvnDeploy,
                             stepInstructions,
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
