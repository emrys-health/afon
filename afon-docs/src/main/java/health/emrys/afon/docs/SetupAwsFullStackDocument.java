package health.emrys.afon.docs;

import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.ops.steps.StackAction.SETUP;

public class SetupAwsFullStackDocument extends AbstractStackDocument {

    public SetupAwsFullStackDocument(AfonPropertyProvider properties) {
        super(properties, SETUP, "aws-full");
    }

    @Override
    public String getFilename() {
        return "setup_aws_full_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "Setup the AWS Full stack (Jupyter, Polaris, Spark etc)";
    }

    public String getFurtherNotes() {
        return String.format(
            """
            # Troubleshooting
            
            ### Problems starting Spark Connect
            
            There appears to be a race condition so that Spark Connect is killed as soon as it starts
            (see issue [#65](https://github.com/emrys-health/afon-core/issues/65)). 
            If this happens, run:
            
            ```
            kubectl delete sparkapp spark-connector
            %s
            ```
            
            ### Problems destroying the stack?
            
           Generate and run cleanup script (more than once if required):
            ```shell
           afon-cli.sh generate-cleanup-script aws-full
            ```
            """, new SparkWorkloads().startConnectCommand());
    }

}
