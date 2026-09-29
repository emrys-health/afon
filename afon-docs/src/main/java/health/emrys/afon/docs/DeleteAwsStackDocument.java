package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonCloud.AWS;

public class DeleteAwsStackDocument extends AbstractStackDocument {

    public DeleteAwsStackDocument(AfonPropertyProvider properties) {
        super(properties, AWS);
    }

    @Override
    public String getFilename() {
        return "delete_aws_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "Deletes the AWS stack.";
    }

    public String getFurtherNotes() {
        return String.format(
            """
            # Troubleshooting
            
            ### Problems destroying the stack?
            
           Generate and run cleanup script (more than once if required):
            ```shell
           afon-cli.sh generate-cleanup-script aws-<stack-type>
            ```
            """);
    }

}
