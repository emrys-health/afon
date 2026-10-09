package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepDocumentation;
import health.emrys.afon.ops.steps.StepOperations;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.util.List;

import static java.lang.String.format;
import static java.lang.String.join;
import static org.slf4j.LoggerFactory.getLogger;

public abstract class AbstractDocument implements DocumentMetadata {

    private static final Logger logger = getLogger(AbstractDocument.class);
    protected final AfonPropertyProvider properties;

    protected AbstractDocument(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public String getDescription() {
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
                    howToRun(),
                    stepsBeingRun(),
                getSupplementaryIntro());
    }

    /**
     * How to run the steps, by default with the command of the ops class named after this document, eg
     * {@code setup-aws-full-stack-with-azure-polaris-hosting-external-catalog} for
     * {@code SetupAwsFullStackWithAzurePolarisHostingExternalCatalogDocument}.
     */
    protected String howToRun() {
        return format(
                """
                
                To execute, run:
                ```shell
                afon-cli.sh %s
                ```
                """, opsCommand());
    }

    /**
     * How to resume the steps from a failed step, eg {@code lastStep}, by default with the command of the ops
     * class named after this document, given the step as its arg.
     */
    protected String howToResume(String lastStep) {
        return format(
                """
                If the job fails on a given step, you don't need to rerun the full list of steps. 
                Instead, you can add to the command the step from which to restart:
                
                `afon-cli.sh %s <one-of-the-steps-from-list-above>`
                
                This will continue the execution starting at the chosen step.                       
                
                For example:
                ```shell
                afon-cli.sh %s %s
                ```
                """,
                opsCommand(),
                opsCommand(),
                lastStep);
    }

    private String opsCommand() {
        return StepDocumentation.afonCliCommand(opsClass());
    }

    private String stepsBeingRun() {
        StepOperations operations = new StepOperations(new StepDocumentation(OutputStream.nullOutputStream()), properties);
        List<Step> steps = steps(operations);
        if (steps.size() == 0){
            return format("The document %s has no steps.", getClass().getName());
        }
        String[] stepNames = steps.stream().map(x -> x.getName()).toArray(String[]::new);
        return format(
                """
                The steps being run are:
                - %s
                
                %s""",
                join(("\n- "), stepNames),
                howToResume(stepNames[stepNames.length - 1])
        );

    }

    private Class<?> opsClass() {
        String opsClassName = getClass().getName().replace("docs.", "ops.").replace("Document", "Main");
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
