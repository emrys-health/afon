package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.Defaults;
import health.emrys.afon.infra.PurgeAndPopulatePolaris;
import health.emrys.afon.infra.checks.SparkCheck;
import org.slf4j.Logger;

import java.util.Properties;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE_EXTERNAL;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static health.emrys.afon.infra.checks.SparkCheck.EXTERNAL_NAMESPACE;
import static java.lang.String.format;

public class StepOperations {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(StepOperations.class);

    private static final Properties NO_ENV_VARIABLES = new Properties();

    private final StepActions actions;

    public StepOperations(StepActions actions) {
        this.actions = actions;
    }

    public Step cleanLocalAws() {
        return new Step("clean_aws",
                        "clean the local AWS directory",
                        () -> {
            try {
                actions.deleteStack(AwsWholeStack.ID);
            } catch (Exception ex) {
                log.error(format("""
                              You may need to run:
                              
                              aws eks update-kubeconfig --region %s --name %s
                              """, Defaults.AWS_REGION, CLUSTER_NAME), ex);
            }});
    }

    public Step deployAwsPolaris() {
        return new Step("aws_polaris",
                        "deploy Polaris in AWS",
                        () -> actions.startAwsPolaris());
    }

    public Step deployAwsSpark() {
        return new Step("aws_spark",
                        "deploy Spark in AWS",
                        () -> actions.startSparkClusterInAws());
    }

    public Step cleanLocalAzure() {
        return new Step("clean_az",
                        "clean the local Azure directory",
                        () -> {
                            actions.deleteStack(AzureKubernetesPolaris.ID);
                        });
    }

    public Step deployAzurePolaris() {
        return new Step("az_polaris",
                 "deploy Polaris in Azure",
                 () -> actions.startAzurePolaris());
    }

    public Step purgeForeignCatalogFromAzure() {
        return new Step("az_foreign_clean",
                        format("remove all persisted references to %s in the Polaris at %s", EXTERNAL_NAMESPACE, AZURE_DOMAIN_NAME),
                        () -> actions.executeMainOf(PurgeAndPopulatePolaris.class,
                                                    new String[] { AZURE_DOMAIN_NAME, AZURE.name(), EXTERNAL_NAMESPACE, SparkCheck.TABLE},
                                                    NO_ENV_VARIABLES));
    }

    public Step createAzureCatalogInAzure() {
        return new Step("az_foreign",
                        format("Create a catalog in the Polaris at `%s`", AZURE_DOMAIN_NAME),
                        () -> {
                            // swap the buckets
                            Properties                   properties = new Properties();
                            properties.setProperty(AZURE_BUCKET.getPropertyName(), AZURE_EXTERNAL_BUCKET.getValueFrom(appProperties()));
                            actions.createSingleCatalogsFor(AZURE_DOMAIN_NAME, AZURE, properties);
                        });
    }

    private static Properties appProperties() {
        return new AfonPropertiesLoader().getProperties();
    }

    public Step createExternalAzureCatalogInAzure() {
        return new Step("az_external_aws",
                        format("configure a catalog called `%s` in the Polaris at `%s`", AZURE_EXTERNAL, AZURE_DOMAIN_NAME),
                        () -> actions.createSingleCatalogsFor(AZURE_DOMAIN_NAME, AZURE_EXTERNAL));
    }

    public Step initializeAllCatalogsInAws() {
        return new Step("aws_all_catalogs",
                 "configure all the the catalogs in Polaris at "+ AWS_POLARIS_DOMAIN_NAME,
                 () -> actions.executeMainOf(PurgeAndPopulatePolaris.class,
                                             new String[] { AWS_POLARIS_DOMAIN_NAME} ,
                                             NO_ENV_VARIABLES));
    }

    public Step waitForAwsPolaris() {
        return actions.waitFor(AWS_POLARIS_DOMAIN_NAME);
    }

    public Step waitForAzurePolaris() {
        return actions.waitFor(AZURE_DOMAIN_NAME);
    }

    public Step checkLocalSparkTalksToAzureViaAws() {
        return new Step("az_external_aws_check",
                        format("run Spark locally such that it talks to %s which defers to %s", AWS_POLARIS_DOMAIN_NAME, AZURE_DOMAIN_NAME),
                        () -> {
                            Properties properties = appProperties();
                            actions.executeMainOf(SparkCheck.class,
                                                  new String[] {AZURE_EXTERNAL.toString(),
                                                                POLARIS_ID.getValueFrom(properties),
                                                                POLARIS_SECRET.getValueFrom(properties),
                                                                AWS_POLARIS_DOMAIN_NAME
                                                                },
                                                  NO_ENV_VARIABLES);
                        });
    }

    private void useAwsKubernetes() throws ManagedProcessException {
        actions.run("kubectl", null, "config", "use-context", "arn:aws:eks:eu-west-2:123456789012:cluster/spark-cluster");
    }
    private void useAzureKubernetes() throws ManagedProcessException {
        actions.run("kubectl", null, "config", "use-context", "spark-cluster");
    }
 }
