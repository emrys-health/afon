package health.emrys.afon.ops.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.io.OutputStream;
import java.util.List;

import static java.lang.String.format;
import static java.lang.String.join;

public abstract class AbstractDocument implements DocumentMetadata {

    protected final AfonPropertyProvider properties;
    protected final AfonUserProvider users;

    protected AbstractDocument(AfonPropertyProvider properties, AfonUserProvider users) {
        this.properties = properties;
        this.users = users;
    }

    public String getDescription() {
        return format(
                """
                # Introduction
                
                The `afon-ops` documentation is generated automatically.
                The generated docs can be found in `afon-ops/target/docs`.  
                
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
     * How to run the steps.
     */
    protected abstract String howToRun();

    /**
     * How to resume the steps from a failed step, eg {@code lastStep}.
     */
    protected abstract String howToResume(String lastStep);

    /**
     * The list of the steps being run, with how to resume from one.
     */
    protected String stepsBeingRun() {
        StepOperations operations = new StepOperations(new DocumentingStepActions(OutputStream.nullOutputStream()), properties, users);
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

    public String getFurtherNotes() {
        return "";
    }

    protected abstract String getSupplementaryIntro();

}
