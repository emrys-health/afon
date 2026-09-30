package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.aws.AwsFullStack;
import health.emrys.afon.cdk.aws.AwsKeycloakStack;
import health.emrys.afon.cdk.aws.AwsPolarisStack;
import health.emrys.afon.cdk.azure.AzureFullStack;
import health.emrys.afon.cdk.azure.AzurePolarisStack;
import health.emrys.afon.cdk.gcp.GcpPolarisStack;
import health.emrys.afon.config.*;
import health.emrys.afon.ops.snowflake.SnowflakeStatements;
import health.emrys.afon.ops.spark.SparkRunner;
import health.emrys.afon.ops.spark.SparkTestReader;
import health.emrys.afon.ops.spark.SparkTestWriter;
import health.emrys.afon.security.keycloak.KeyCloakConfigurer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.steps.DurationUtils.formatDuration;
import static health.emrys.afon.ops.steps.DurationUtils.parseDuration;
import static java.lang.String.format;

public class StepOperations {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StepOperations.class);

    private final StepActions actions;

    private final AfonPropertyProvider properties;
    private final AfonCatalogs afonCatalogs;
    private final String polarisNamespace;
    private final String polarisNamespaceExternal;
    private final String polarisTableName;
    private final Map<AfonCloud, Duration> deleteDurations = new EnumMap<>(AfonCloud.class);
    private final Map<AfonCloud, Duration> deployDurations = new EnumMap<>(AfonCloud.class);

    public StepOperations(StepActions actions, AfonPropertyProvider properties) {
        this.actions = actions;
        this.properties = properties;
        this.polarisNamespace = properties.getValue(POLARIS_NAMESPACE);
        this.polarisNamespaceExternal = properties.getValue(POLARIS_NAMESPACE_EXTERNAL);
        this.polarisTableName = properties.getValue(POLARIS_TABLE_NAME);
        this.afonCatalogs = new AfonCatalogs(properties);
    }

    public AfonCatalogs afonCatalogs() {
        return afonCatalogs;
    }

    /**
     * Deletes every stack in the cloud, each having its own stack dir and tofu state.
     */
    @NonNull
    public Step deleteStackIn(final AfonCloud cloud) {
        return deleteStacksIn(cloud, format("delete_%s_stack", cloud), StackFilter.ALL);
    }

    /**
     * Deletes the stacks in the cloud selected by the filter, eg those conflicting with the stack to deploy.
     */
    @NonNull
    public Step deleteStacksIn(final AfonCloud cloud, String stepName, StackFilter filter) {
        logger.info(format("Deleting %s in cloud %s", filter.description(), cloud));
        return new Step(stepName,
                format("delete %s in %s", filter.description(), cloud),
                () -> timed(deleteDurations, cloud, () -> actions.deleteStacksIn(cloud, filter, tofuOutput())));
    }

    /**
     * Checks that the KeyCloak server at {@code OIDC_DOMAIN_NAME}, deployed by another stack, eg {@code aws-keycloak},
     * answers, so that a stack using it, eg {@code aws-polaris}, fails fast rather than when Polaris cannot discover it.
     * It asks for the discovery document of the {@code master} realm, as it always exists, unlike the Polaris realm,
     * which the setup steps create.
     */
    public Step checkKeyCloakAnswers() {
        String url = KeyCloakConfigurer.urlFor(properties.getValue(OIDC_DOMAIN_NAME), "https")
                     + "/realms/master/.well-known/openid-configuration";
        return new Step("check_keycloak_answers",
                format("check KeyCloak answers at %s", url),
                () -> actions.checkAnswers(url,
                    "Deploy the KeyCloak stack at OIDC_DOMAIN_NAME first, eg: afon-cli.sh manage-stack setup aws-keycloak"));
    }

    public Step deployFullStackIn(AfonCloud cloud) {
        logger.info(format("Deploying full stack in cloud %s", cloud));
        switch (cloud) {
            case AWS:
                return new Step("deploy_aws_full_stack",
                        "deploy full stack in AWS",
                        () -> {
                            checkAwsCertificatesIssued(AWS_KEYCLOAK_CERT_ARN,
                                    AWS_JUPYTER_CERT_ARN,AWS_POLARIS_CERT_ARN);
                            timed(deployDurations, AWS, () -> actions.startStack(AwsFullStack.class, tofuOutput()));
                        });
            case AZURE:
                return new Step("deploy_azure_full_stack",
                        "deploy full stack in Azure",
                        () -> timed(deployDurations, AZURE, () -> actions.startStack(AzureFullStack.class, tofuOutput())));
            case GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    /**
     * Runs the operation, recording its elapsed time for the cloud, so that it can be reported
     * by the *Complete steps.
     */
    private static void timed(Map<AfonCloud, Duration> durations, AfonCloud cloud, ThrowableRunnable operation) throws Exception {
        long start = System.nanoTime();
        try {
            operation.run();
        } finally {
            durations.put(cloud, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * Describes the recorded durations of the delete and deploy operations for the cloud,
     * e.g. {@code " (delete: 3m12s, deploy: 25m4s)"}, or empty if none recorded.
     */
    private String elapsed(AfonCloud cloud) {
        List<String> parts = new ArrayList<>();
        if (deleteDurations.containsKey(cloud)) {
            parts.add("delete: " + formatDuration(deleteDurations.get(cloud).toMillis()));
        }
        if (deployDurations.containsKey(cloud)) {
            parts.add("deploy: " + formatDuration(deployDurations.get(cloud).toMillis()));
        }
        return parts.isEmpty() ? "" : format(" (%s)", String.join(", ", parts));
    }

    /**
     * Fails fast, before the slow {@code tofu apply} even starts, if any configured ACM
     * certificate isn't actually {@code ISSUED}. Without this, a stale/dead cert (e.g. one
     * stuck at {@code VALIDATION_TIMED_OUT}) only surfaces ~20 minutes into the apply, buried
     * in a generic "Load Balancer is not ready yet" error from the ALB controller endlessly
     * retrying {@code CreateListener} against a certificate AWS will never accept. Re-issue a
     * failing one with {@code scripts/aws/issue-cert.sh}.
     */
    void checkAwsCertificatesIssued(AfonProperty... arns) throws ManagedProcessException {
        String region = properties.getValue(AWS_REGION);

        List<String> problems = new ArrayList<>();
        for (AfonProperty property : arns) {
            String arn = properties.getValue(property);
            if (arn == null || arn.isBlank()) {
                return;
            }
            String status = describeCertificateStatus(arn, region);
            if (!"ISSUED".equals(status)) {
                problems.add(format("%s is %s, not ISSUED", property, status));
            }
        }
        if (!problems.isEmpty()) {
            throw new ManagedProcessException(
                "ACM certificate(s) not ready for use - re-issue with scripts/aws/issue-cert.sh, "
              + "update the property, then retry:\n" + String.join("\n", problems));
        }
    }

    private String describeCertificateStatus(String certArn, String region) throws ManagedProcessException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        actions.awsDescribeCertificate(certArn, region, out);
        return out.toString().trim();
    }

    public Step deployPolarisStackIn(AfonCloud cloud) {
        logger.info(format("Deploying Polaris stack in cloud %s", cloud));
        switch (cloud) {
            case AWS:
                return new Step("deploy_aws_polaris_stack",
                        "deploy Polaris stack in AWS",
                        () -> {
                            checkAwsCertificatesIssued(AWS_POLARIS_CERT_ARN);
                            timed(deployDurations, AWS, () -> actions.startStack(AwsPolarisStack.class, tofuOutput()));
                        });
            case AZURE:
                return new Step("deploy_azure_polaris_stack",
                        "deploy Polaris stack in Azure",
                        () -> timed(deployDurations, AZURE, () -> actions.startStack(AzurePolarisStack.class, tofuOutput())));
            case GCP:
                return new Step("deploy_gcp_polaris_stack",
                        "deploy Polaris stack in GCP",
                        () -> timed(deployDurations, GCP, () -> actions.startStack(GcpPolarisStack.class, tofuOutput())));
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public Step deployKeycloakStackIn(AfonCloud cloud) {
        logger.info(format("Deploying KeyCloak stack in cloud %s", cloud));
        switch (cloud) {
            case AWS:
                return new Step("deploy_aws_keycloak_stack",
                        "deploy KeyCloak stack in AWS",
                        () -> {
                            checkAwsCertificatesIssued(AWS_KEYCLOAK_CERT_ARN);
                            timed(deployDurations, AWS, () -> actions.startStack(AwsKeycloakStack.class, tofuOutput()));
                        });
            case AZURE, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public Step waitForPolarisAndKeycloakIn(AfonCloud cloud) {
        return waitForPolaris(cloud).andThen(waitForKeyCloak());
    }

    private Step waitForPolaris(AfonCloud cloud) {
        long timeout = dnsWaitTimeout();
        switch (cloud) {
            case AWS:
                return actions.waitOnDNS(properties.getValue(AWS_POLARIS_DOMAIN_NAME), timeout);
            case AZURE:
                return actions.waitOnDNS(properties.getValue(AZURE_POLARIS_DOMAIN_NAME), timeout);
            case GCP:
                return actions.waitOnDNS(properties.getValue(GCP_POLARIS_DOMAIN_NAME), timeout);
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public Step waitForKeyCloak() {
        long timeout = dnsWaitTimeout();
        return actions.waitOnDNS(properties.getValue(OIDC_DOMAIN_NAME), timeout);
    }

    private long dnsWaitTimeout() {
        String defaultValue = "20m";
        String value = properties.getValue(DNS_WAIT_TIMEOUT, defaultValue);
        if ( value.isBlank() ){ // allow for blank value
            value = defaultValue;
        }
        return parseDuration(value).toMillis();
    }

    /**
     * Resolves the stream to which the tofu output is written from {@link AfonProperty#TOFU_OUTPUT}:
     * "logger" (the default, also when blank) to leave it only to the {@code ch.vorburger.exec.ManagedProcess}
     * logger, which always logs it, or "console" to also write it to {@code System.out}.
     */
    OutputStream tofuOutput() {
        String defaultValue = "logger";
        String value = properties.getValue(TOFU_OUTPUT, defaultValue);
        if ( value.isBlank() ){ // allow for blank value
            value = defaultValue;
        }
        switch (value.trim().toLowerCase()) {
            case "console":
                return System.out;
            case "logger":
                return OutputStream.nullOutputStream();
            default:
                throw new IllegalArgumentException(format(
                    "Unsupported %s value '%s', must be one of 'console' or 'logger'", TOFU_OUTPUT, value));
        }
    }

    public Step createAllCatalogsFor(AfonCloud cloud) {
        return new Step(format("create_all_catalogs_for_cloud_%s", cloud),
                format("create all Polaris catalogs for cloud `%s`", cloud),
                () -> actions.createAllCatalogsFor(cloud));
    }

    public Step configureOidcUsersFor(AfonCloud cloud) {
        return new Step(format("configure_oidc_users_for_cloud_%s", cloud),
                format("configure OIDC users for cloud `%s`", cloud),
                () -> actions.configureOidcUsersFor(cloud));
    }

    /**
     * Configures Polaris users on every catalog, for every user - must run after
     * {@link #createAllCatalogsFor} (the catalogs must exist) and after {@link #configureOidcUsersFor}
     * (once Polaris defers OAuth to KeyCloak, this authenticates as a KeyCloak confidential client
     * that only that step creates).
     */
    public Step configurePolarisUsersOnAllCatalogsFor(AfonCloud cloud) {
        // Not glue: it isn't created by createAllCatalogsFor, so there'd be no catalog to configure.
        return afonCatalogs.allExcluding("glue").stream()
            .map(catalog -> configurePolarisUsers(cloud, catalog))
            .reduce(Step::andThen)
            .orElse(Step.NoOp);
    }

    private @NonNull Step configurePolarisUsers(AfonCloud cloud, AfonCatalog catalog) {
        return new Step(format("configure_polaris_users_on_catalog_%s_for_cloud_%s", catalog, cloud),
                format("configure Polaris users on catalog `%s` for cloud `%s`", catalog, cloud),
                () -> {
                    actions.configurePolarisUsersFor(cloud, catalog);
                });
    }

    public Step createExternalCatalogFor(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return createCatalogFor(AZURE, afonCatalogs.azureExternal());
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private @NonNull Step createCatalogFor(AfonCloud cloud, AfonCatalog catalog) {
        return new Step(format("create_catalog_%s_for_cloud_%s", catalog, cloud),
            format("create Polaris catalog `%s` for cloud `%s`", catalog, cloud),
            () -> actions.createCatalogFor(cloud, catalog));
    }

    private @NonNull Step deleteCatalogFor(AfonCloud cloud, AfonCatalog catalog) {
        return new Step(format("delete_catalog_%s_for_cloud_%s", catalog, cloud),
                format("delete Polaris catalog `%s` for cloud `%s`", catalog, cloud),
                () -> actions.deleteCatalogFor(cloud, catalog));
    }

    public Step deleteExternalPolarisTableFor(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return deleteExternalPolarisTableFor(AZURE, afonCatalogs.azure());
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public Step deleteAndRecreateAwsPolarisCatalogFor(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                AfonCatalog azureCatalog = afonCatalogs.azure();
                return deleteCatalogFor(AWS, azureCatalog)
                        .andThen(createCatalogFor(AWS, azureCatalog));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private @NonNull Step deleteExternalPolarisTableFor(AfonCloud cloud, AfonCatalog catalog) {
        return deletePolarisTable(cloud, catalog, polarisNamespaceExternal, polarisTableName);
    }

    private @NonNull Step deletePolarisTable(AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName) {
        return new Step(format("delete_table_%s.%s.%s_for_cloud_%s", catalog, namespace, tableName, cloud),
                format("delete Polaris table `%s.%s.%s` for cloud `%s`", catalog, namespace, tableName, cloud),
                () -> actions.deleteTableFor(cloud, catalog, namespace, tableName));
    }

    public Step runAwsSparkWritingTo(AfonCloud cloud) {
        switch (cloud) {
            case AWS:
                return awsSparkWritingTo(afonCatalogs.aws(), polarisNamespaceExternal);
            case AZURE:
                return awsSparkWritingTo(afonCatalogs.azure(), polarisNamespaceExternal);
            case GCP:
                return awsSparkWritingTo(afonCatalogs.gcp(), polarisNamespaceExternal);
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step awsSparkWritingTo(AfonCatalog catalog, String namespace) {
        AfonCloud cloud = AWS;
        return configurePolarisUsers(cloud, catalog)
                .andThen(runSpark("Spark writing table",
                        cloud, catalog, namespace, polarisTableName, SparkTestWriter.class));
    }

    public @NonNull Step runAwsSparkDeferringToAzurePolarisHostingExternalCatalog() {
        AfonCloud cloud = AWS;
        AfonCatalog catalog = afonCatalogs.azureExternal();
        return configurePolarisUsers(cloud, catalog)
                .andThen(runSpark(format("Spark defers to %s writing table", AZURE),
                        cloud, catalog, polarisNamespaceExternal, polarisTableName, SparkTestWriter.class));
    }


    public @NonNull Step runAwsSparkReadingFrom(AfonCatalog catalog) {
        AfonCloud cloud = AWS;
        return configurePolarisUsers(cloud, catalog)
                .andThen(runSpark("Spark reading table",
                        cloud, catalog, polarisNamespace, polarisTableName, SparkTestReader.class));
    }

    private @NonNull Step runSpark(String description, AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName, Class<? extends SparkRunner> sparkRunner) {
        return new Step(format("%s_spark_talks_to_catalog_%s", cloud, catalog),
                format("%s `%s.%s.%s` on cloud `%s`", description, catalog, namespace, tableName, cloud),
            () -> {
                actions.runSpark(sparkRunner, cloud, catalog, namespace, tableName);
            });
    }

    public @NonNull Step snowflakeReadingViaAwsPolaris(AfonCatalog catalog) {
        return new Step(format("snowflake_reads_catalog_%s", catalog),
            format("Snowflake using AWS Polaris to read catalog %s", catalog),
            () -> {
                logger.warn("Calling Snowflake. Note that some of these statements can take a long time");
                actions.runSnowflakeSql(properties, SnowflakeStatements.readingViaAwsPolaris(catalog, properties));
            }
        );
    }

    public @NonNull Step snowflakeWritingTo(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return snowflakeWritingToAzure();
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step snowflakeWritingToAzure() {
        return new Step("snowflake_writes_to_azure",
            "Snowflake writes sample data to Azure blob storage",
            () -> actions.runSnowflakeSql(properties, SnowflakeStatements.writingToAzure(properties)));
    }

    public @NonNull Step registerMostRecentSnowflakeTableInAws(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                String glob = format(
                    "%s/%s*metadata.json",
                    polarisNamespace,
                    SnowflakeStatements.SNOWFLAKE_OUT_DIR);
                String json = actions.azureFirstFile(properties, glob, true); // firstFileInLatestDirectoryMatching(glob);
                return registerTableInAwsPolaris(afonCatalogs.azure(), polarisNamespace, polarisTableName, json);
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step registerTableInAwsPolaris(AfonCatalog catalog, String namespace, String tableName, String metadata) {
        return new Step(format("register_table_%s_in_aws_polaris", tableName),
                format("Register table `%s` in the AWS Polaris", tableName),
                () -> actions.registerTable(AWS, catalog, namespace, tableName, metadata));
    }

    public Step deleteComplete(AfonCloud cloud) {
        return new Step(format("delete_complete_for_cloud_%s", cloud),
                format("Delete complete for cloud %s", cloud),
                ()-> logger.info(format("Delete complete for %s%s", cloud, elapsed(cloud))));
    }

    public Step deployComplete(AfonCloud cloud) {
        return new Step(format("deploy_complete_for_cloud_%s", cloud),
                format("Deploy complete for cloud %s", cloud),
                ()-> logger.info(format("Deploy complete for %s%s", cloud, elapsed(cloud))));
    }

    public Step setupComplete(AfonCloud cloud) {
        return new Step(format("setup_complete_for_cloud_%s", cloud),
        format("Setup complete for cloud %s", cloud),
                ()-> logger.info(format("Setup complete for cloud %s%s", cloud, elapsed(cloud))));
    }

}
