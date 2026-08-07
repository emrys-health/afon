package health.emrys.afon.docs;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepDocumentation;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepsProvider;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public abstract class AbstractDocumentMetadata implements DocumentMetadata {

    private static final Logger log = getLogger(AbstractDocumentMetadata.class);

    public String getDescription() {
        String afonExec        = "";
        String stepInstructions = "";
        try {
            Class<?> clazz = getDeployHomologue();
            afonExec = String.format(
                """
                
                To execute, run:
                ```shell
               ./afon.sh %s 
                ```
                """, StepDocumentation.afonArgumentsToRun(clazz, null, "exec"));
            Object o = clazz.getConstructor(new Class[]{}).newInstance(new Object[]{});
            if (o instanceof StepsProvider) {
                StepOperations dummyOps  = new StepOperations(new StepDocumentation(OutputStream.nullOutputStream()));
                List<Step>     steps     = ((StepsProvider) o).stepsUsing(dummyOps);
                String[]       stepArray = steps.stream().map(x -> x.getName()).toArray(String[]::new);
                if (stepArray.length > 0) {
                    stepInstructions = format(
                        """
                        
                        The steps to run are:
                        - %s
                        
                        If the job fails on a given step, you don't need to rerun the full list of steps. 
                        Instead, you can specify to the exec command the step from which to restart:
                        
                        `--exec-args "<one-of-the-steps-from-list-above>"`

                        This will continue the execution starting at the chosen step.                       
                        
                        For example:
                        ```shell
                        ./afon.sh -a exec --exec-class "%s" --exec-args "%s"
                        ```
                        """,
                        String.join(("\n- "), stepArray),
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
            
            To generate the documentation, run:
            ```shell
            # builds docs without acceptance tests
            ./afon.sh -a build -p -acceptance
            ```
            The generated docs can be found in `afon-docs/target/docs`.  To export them to an external dir:
            ```shell
            # retrieves docs and copies them to a specified docs dir, eg:
            ./afon.sh -a docs --docs-dir ~/.afon/docs
            ```
            
            %s
            %s
            %s
            """,
            afonExec,
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
