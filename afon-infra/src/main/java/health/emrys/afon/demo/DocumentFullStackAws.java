package health.emrys.afon.demo;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentFullStackAws implements DocumentMetadata {

    private static final Logger log = getLogger(DocumentFullStackAws.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentFullStackAws()).writeDocumentation();
    }

    @Override
    public String getDescription() {
        return "This brings the full stack (Jupyter, Polaris, Spark etc) up on AWS.";
    }

    @Override
    public String getFilename() {
        return "full_stack_aws.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeployAwsFulltStackMain.steps(stepOperations);
    }
}
