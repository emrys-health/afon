package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.SetupAzurePolarisStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class SetupAzurePolarisStackDocument extends AbstractDocument {

    public SetupAzurePolarisStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "setup_azure_polaris_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup an Azure environment with a Polaris instance populated with the test catalogs
                """;
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args azure
            ```
            """);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new SetupAzurePolarisStackMain().stepsUsing(stepOperations);
    }

}
