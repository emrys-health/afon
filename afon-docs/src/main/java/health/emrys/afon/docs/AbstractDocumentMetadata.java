package health.emrys.afon.docs;

import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepDocumentation;
import health.emrys.afon.steps.StepOperations;
import health.emrys.afon.steps.StepsProvider;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public abstract class AbstractDocumentMetadata implements DocumentMetadata {

    private static final Logger log = getLogger(AbstractDocumentMetadata.class);

    public String getDescription() {
        String mvnDeploy        = "";
        String stepInstructions = "";
        try {
            Class<?> clazz = getDeployHomologue();
            mvnDeploy = String.format(
                """
                
                To execute, run:
                ```shell
                mvn -Pexec %s 
                ```
                """, StepDocumentation.mvnArgumentsToRun(clazz, null, "exec"));
            Object o = clazz.getConstructor(new Class[]{}).newInstance(new Object[]{});
            if (o instanceof StepsProvider) {
                StepOperations dummyOps  = new StepOperations(new StepDocumentation(OutputStream.nullOutputStream()));
                List<Step>     steps     = ((StepsProvider) o).stepsUsing(dummyOps);
                String[]       stepArray = steps.stream().map(x -> x.getName()).toArray(String[]::new);
                if (stepArray.length > 0) {
                    stepInstructions = format(
                        """
                        
                        The steps to run are:
                        1. %s
                        
                        If the job fails on a given step, you can add this switch to the mvn command above:
                        
                        `-Dexec.args="STEP_FROM_LIST_ABOVE"`
                        
                        and the steps will continue starting at that one.
                        
                        For example:
                        
                        `mvn exec:exec -Pexec -Dexec.mainClass="%s"  -Dexec.args="%s"`
                        """,
                        String.join(("\n1. "), stepArray),
                        clazz.getCanonicalName(),
                        stepArray[stepArray.length - 1]
                    );
                }
            }
        } catch (ClassNotFoundException e) {
            log.warn("Cannot find the class for " + this.getClass().getSimpleName(), e);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                 IllegalAccessException e) {
            log.warn("Could not find the steps for the class", e);
        }
        return String.format(
            """
            # Introduction
            
            To create this documentation, run:
            ```
            mvn %s
            ```
            %s
            %s
            %s
            """, StepDocumentation.mvnArgumentsToRun(this.getClass(), null, "java"),
            mvnDeploy,
            stepInstructions,
            getSupplementaryIntro());
    }

    public Class<?> getDeployHomologue() throws ClassNotFoundException {
        String name = this.getClass().getName();
        return Class.forName(name.replace("docs.Document", "ops.Deploy"));
    }

    abstract public String getSupplementaryIntro();

    public String getFurtherNotes() {
        return "";
    }
}
