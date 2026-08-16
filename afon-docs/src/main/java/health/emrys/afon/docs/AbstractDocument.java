package health.emrys.afon.docs;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepDocumentation;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepsProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.util.List;
import java.util.Properties;

import static java.lang.String.format;
import static java.lang.String.join;
import static org.slf4j.LoggerFactory.getLogger;

public abstract class AbstractDocument implements DocumentMetadata {

    private static final Logger logger = getLogger(AbstractDocument.class);
    protected final Properties properties;

    protected AbstractDocument(Properties properties) {
        this.properties = properties;
    }

    public String getDescription() {
        Class<?> deployClass = opsClass(this.getClass().getName());
        return format(
                """
                # Introduction
                
                To generate the documentation, run:
                ```shell
                # builds docs without acceptance tests
                ./afon.sh -a build -p -acceptance
                ```
                The generated docs can be found in `afon-docs/target/docs`.  To export all the docs (including the static) to an external dir:
                ```shell
                # export docs to a specified docs dir, eg:
                ./afon.sh -a docs-export --docs-dir ~/.afon/docs
                ```
                # Deployment
                
                %s
                %s
                %s
                """,
                    afonExec(deployClass),
                    stepsBeingRun(deployClass),
                getSupplementaryIntro());
    }

    private static @NonNull String afonExec(Class<?> deployClass) {
        return format(
                """
                
                To execute, run:
                ```shell
               ./afon.sh %s 
                ```
                """, StepDocumentation.afonArgumentsToRun(deployClass, null, "exec"));
    }

    private static String stepsBeingRun(Class<?> deployClass) {
        StepsProvider provider = stepProvider(deployClass);
        List<Step> steps = provider.stepsUsing(new StepOperations(new StepDocumentation(OutputStream.nullOutputStream())));
        if (steps.size() == 0){
            return format("The steps provider %s has no steps.", deployClass.getName());
        }
        String[] stepNames = steps.stream().map(x -> x.getName()).toArray(String[]::new);
        return format(
                """
                The steps being run are:
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
                join(("\n- "), stepNames),
                deployClass.getCanonicalName(),
                stepNames[stepNames.length - 1]
        );

    }

    private static @NonNull StepsProvider stepProvider(Class<?> deployClass) {
        try {
            return (StepsProvider)deployClass.getConstructor(new Class[]{}).newInstance(new Object[]{});
        } catch (Exception e) {
            throw new RuntimeException("Not a StepsProvider: " + deployClass.getName(), e);
        }
    }

    private Class<?> opsClass(String documentClassName) {
        String opsClassName = documentClassName.replace("docs.", "ops.").replace("Document", "Main");
        try {
            return Class.forName(opsClassName);
        } catch (ClassNotFoundException e) {
            logger.warn("Class not found: " + opsClassName, e);
            throw new RuntimeException("Class not found: "+opsClassName, e);
        }
    }

    public String getFurtherNotes() {
        return "";
    }

    protected abstract String getSupplementaryIntro();

}
