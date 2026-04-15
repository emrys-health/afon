package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.cdk.GcpKubernetesPolaris;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.Defaults;
import health.emrys.afon.infra.PurgeAndPopulatePolaris;
import health.emrys.afon.infra.checks.SparkCheck;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.Properties;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.cdk.GcpKubernetesPolaris.GCP_DOMAIN_NAME;
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
                        "clean the local AWS directory (if it exists)",
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

    public Step deployGcpPolaris() {
        return new Step("gcp_polaris",
                        "deploy Polaris in GCP",
                        () -> actions.startGcpPolaris());
    }

    public Step cleanLocalAzure() {
        return cleanLocal(AzureKubernetesPolaris.ID);
    }

    @NonNull
    public Step cleanLocal(SupportedCloudsEnum id) {
        return new Step("clean_" + id,
                        format("clean the local %s directory (if it exists)", id),
                        () -> {
                            actions.deleteStack(id);
                        });
    }

    public Step deployAzurePolaris() {
        return new Step("az_polaris",
                 "deploy Polaris in Azure",
                 () -> actions.startAzurePolaris());
    }

    public Step purgeForeignCatalogFromAzure() {
        return purgeForeignCatalogFrom(AZURE_DOMAIN_NAME, AZURE);
    }

    public @NonNull Step purgeForeignCatalogFrom(String domainName,
                                                  SupportedCloudsEnum catalog) {
        String name = machineNameFrom(domainName);
        return new Step(name + "_foreign_clean",
                        format("remove all persisted references to %s in the Polaris at %s", EXTERNAL_NAMESPACE, domainName),
                        () -> actions.executeMainOf(PurgeAndPopulatePolaris.class,
                                                    new String[]{domainName, catalog.name(), EXTERNAL_NAMESPACE, SparkCheck.TABLE},
                                                    NO_ENV_VARIABLES));
    }

    private static @NonNull String machineNameFrom(String domainName) {
        String name = domainName;
        if (domainName.contains(".")) {
            name = domainName.substring(0, domainName.indexOf("."));
        }
        return name;
    }

    public Step createAzureCatalogInAzure() {
        String domainName = AZURE_DOMAIN_NAME;
        return createAzureCatalogIn(domainName);
    }

    public @NonNull Step createAzureCatalogIn(String domainName) {
        return new Step("foreign_catalog_in_" + machineNameFrom(domainName),
                        format("Create a catalog in the Polaris at `%s`", domainName),
                        () -> {
                            // swap the buckets
                            Properties properties = new Properties();
                            properties.setProperty(AZURE_BUCKET.getPropertyName(), AZURE_EXTERNAL_BUCKET.getValueFrom(appProperties()));
                            actions.createSingleCatalogsFor(domainName, AZURE, properties);
                        });
    }

    private static Properties appProperties() {
        return new AfonPropertiesLoader().getProperties();
    }

    public Step createExternalAzureCatalogInAzure() {
        return createExternalAzureCatalogIn(AZURE_DOMAIN_NAME);
    }

    public @NonNull Step createExternalAzureCatalogIn(String domainName) {
        return new Step("external_aws_in_" + machineNameFrom(domainName),
                        format("configure a catalog called `%s` in the Polaris at `%s`", AZURE_EXTERNAL, domainName),
                        () -> actions.createSingleCatalogsFor(domainName, AZURE_EXTERNAL));
    }

    public Step initializeAllCatalogsInAws() {
        return initializeAllCatalogsIn(AWS_POLARIS_DOMAIN_NAME);
    }

    @NonNull
    public Step initializeAllCatalogsIn(String domainName) {
        return new Step("all_catalogs_in_" + machineNameFrom(domainName),
                        "configure all the the catalogs in Polaris at " + domainName,
                        () -> actions.executeMainOf(PurgeAndPopulatePolaris.class,
                                                    new String[]{domainName},
                                                    NO_ENV_VARIABLES));
    }

    public Step waitForAwsPolaris() {
        return actions.waitFor(AWS_POLARIS_DOMAIN_NAME);
    }

    public Step waitForAzurePolaris() {
        return actions.waitFor(AZURE_DOMAIN_NAME);
    }

    public Step waitFor(String domainName) {
        return actions.waitFor(domainName);
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
        actions.run("kubectl", null, "config", "use-context", "arn:aws:eks:eu-west-2:418295715997:cluster/spark-cluster");
    }
    private void useAzureKubernetes() throws ManagedProcessException {
        actions.run("kubectl", null, "config", "use-context", "spark-cluster");
    }
 }
