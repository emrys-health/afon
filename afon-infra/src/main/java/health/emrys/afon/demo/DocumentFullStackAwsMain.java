package health.emrys.afon.demo;

import health.emrys.afon.cdk.AwsConstruct;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentFullStackAwsMain extends AbstractDocumentMetadata {

    private static final Logger log = getLogger(DocumentFullStackAwsMain.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentFullStackAwsMain()).writeDocumentation();
    }

    @Override
    public String getSupplementaryIntro() {
        return "This brings the full stack (Jupyter, Polaris, Spark etc) up on AWS.";
    }

    @Override
    public String getFilename() {
        return "full_stack_aws.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeployFullStackAwsMain.steps(stepOperations);
    }

    public String getFurtherNotes() {
        return String.format("""
            # Problems destroying the stack?
            
            Run this *twice*:
            ```shell
            %s
            ```
            """, AwsConstruct.cleanUpHint());
    }
}
