package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.cdk.StackDefinition;
import health.emrys.afon.cdk.StackDefinitions;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static health.emrys.afon.config.ConfigUtils.tfStatePath;
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
    /**
     * The durations of the delete and deploy operations, by the stack they are for, eg {@code aws-full}, or
     * {@code <cloud>-all} for deleting every stack of the cloud.
     */
    private final Map<StackId, Duration> deleteDurations = new TreeMap<>();
    private final Map<StackId, Duration> deployDurations = new TreeMap<>();

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
        StackId all = StackId.all(cloud);
        return deleteStacksIn(all, format("delete_%s_stack", stepName(all)), StackFilter.ALL);
    }

    /**
     * Deletes the stacks in the cloud of the stack selected by the filter, eg those conflicting with the stack to deploy.
     *
     * @param stackId the stack the stacks are deleted for, eg the stack to deploy, or {@code <cloud>-all}
     */
    @NonNull
    public Step deleteStacksIn(final StackId stackId, String stepName, StackFilter filter) {
        AfonCloud cloud = stackId.cloud();
        logger.info(format("Deleting %s in cloud %s", filter.description(), cloud));
        return new Step(stepName,
                format("delete %s in %s", filter.description(), cloud),
                () -> timed(deleteDurations, stackId, () -> actions.deleteStacksIn(
                    cloud, filter, tofuOutput(), this::validatePlanBeforeDeleting)));
    }

    /**
     * @return the config the steps are created with, eg which exclusive resources the stacks hold
     */
    public AfonPropertyProvider properties() {
        return properties;
    }

    /**
     * Checks that the KeyCloak server at {@code OIDC_DOMAIN_NAME}, deployed by another stack, eg {@code aws-keycloak},
     * answers, so that a stack using it, eg {@code aws-polaris}, fails fast rather than when Polaris cannot discover it.
     * It asks for the discovery document of the {@code master} realm, as it always exists, unlike the Polaris realm,
     * which the setup steps create.
     *
     * @param cloud the cloud of the stack using the KeyCloak, whose keycloak stack the failure says to set up, eg
     *              {@code azure-keycloak}, or that of another cloud if it has none, eg {@code aws-keycloak} for GCP
     */
    public Step checkKeyCloakAnswers(AfonCloud cloud) {
        String url = KeyCloakConfigurer.urlFor(properties.getValue(OIDC_DOMAIN_NAME), "https")
                     + "/realms/master/.well-known/openid-configuration";
        return new Step("check_keycloak_answers",
                format("check KeyCloak answers at %s", url),
                () -> actions.checkAnswers(url, format(
                    "Deploy the KeyCloak stack at OIDC_DOMAIN_NAME first, eg: afon-cli.sh manage-stack setup %s",
                    keyCloakStackFor(cloud))));
    }

    /**
     * @return the keycloak stack of the cloud, if it has one, else the first one defined, which any cloud's stacks can use
     */
    private static StackId keyCloakStackFor(AfonCloud cloud) {
        StackId own = new StackId(cloud, "keycloak");
        return StackDefinitions.find(own).isPresent()
               ? own
               : StackDefinitions.all().stream().map(StackDefinition::id).filter(id -> id.type().equals("keycloak"))
                                 .findFirst().orElse(own);
    }

    /**
     * Checks that the Polaris of the cloud answers, ie that its stack is set up, as a Polaris that needs authentication answers
     * a call without it with 401.  It asks for the config of the catalog API, which it answers before any catalog exists.
     */
    public Step checkPolarisAnswers(AfonCloud cloud) {
        String url = "https://" + properties.getCloudValue(cloud, "POLARIS_DOMAIN_NAME") + "/api/catalog/v1/config";
        StackId polaris = new StackId(cloud, "polaris");
        return new Step(format("check_polaris_answers_for_cloud_%s", cloud),
                format("check Polaris answers at %s", url),
                () -> actions.checkAnswers(url,
                    format("Set up the %s Polaris stack first, eg: afon-cli.sh manage-stack setup %s", cloud, polaris),
                    Set.of(200, 401, 403)));
    }

    /**
     * Starts the stack, after synthesising its plan from the config these operations were created with.
     */
    private void startStack(StackId stackId) throws Exception {
        timed(deployDurations, stackId, () -> {
            actions.synthesiseStack(stackId, properties);
            actions.startStack(stackId, tofuOutput());
        });
    }

    /**
     * Validates the plan of a deployed stack from the current config, so that {@code tofu destroy} doesn't
     * run on the plan left by the last deploy or validation, which can be stale, eg after a change of config.
     * It also fails early, before anything is destroyed, if the stack no
     * longer validates, eg because of a missing property.  Does nothing if the stack is not deployed, ie has no
     * tofu state, or has no definition, as the legacy stacks.
     * @throws IllegalStateException if the stack fails to synthesise
     */
    void validatePlanBeforeDeleting(StackId stackId) {
        validatePlanBeforeDeleting(stackId, Path.of(tfStatePath(stackId)));
    }

    void validatePlanBeforeDeleting(StackId stackId, Path tfState) {
        if (!Files.exists(tfState)) {
            return;
        }
        if (new CloudStackValidator().resolve(stackId.toString()) == null) {
            logger.info("No definition of stack {}, so not validating it before deleting it", stackId);
            return;
        }
        logger.info("Validating stack {} again before deleting it, so that its plan is current", stackId);
        try {
            actions.synthesiseStack(stackId, properties);
        } catch (Exception cause) {
            throw new IllegalStateException(String.format(
                "Cannot delete stack %s, as it failed to validate against the current config: %s%n"
                + "Fix the config, as validated with 'afon-cli.sh validate-stack %s', and run again.",
                stackId, cause.getMessage(), stackId), cause);
        }
    }

    /**
     * Deploys the stack, after checking that the certificates it needs are issued, if any, ie on AWS.
     * Applies to any {@link StackId} defined in {@link health.emrys.afon.cdk.StackDefinitions}.
     *
     * @param certificates the properties of the ARNs of the ACM certificates the stack's load balancers use
     */
    public Step deployStack(StackId stackId, List<AfonProperty> certificates) {
        logger.info(format("Deploying stack %s", stackId));
        return new Step(format("deploy_%s_stack", stepName(stackId)),
                format("deploy stack %s", stackId),
                () -> {
                    if (!certificates.isEmpty()) {
                        checkCertificatesIssued(certificates.toArray(new AfonProperty[0]));
                    }
                    startStack(stackId);
                });
    }

    /**
     * Runs the operation, recording its elapsed time for the stack, so that it can be reported
     * by the *Complete steps.
     */
    private static void timed(Map<StackId, Duration> durations, StackId stackId, ThrowableRunnable operation) throws Exception {
        long start = System.nanoTime();
        try {
            operation.run();
        } finally {
            durations.put(stackId, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * Describes the recorded durations of the delete and deploy operations for the stack,
     * e.g. {@code " (delete: 3m12s, deploy: 25m4s)"}, or empty if none recorded.  The delete is the one for the
     * stack, else the one deleting every stack of its cloud, eg before deploying it.
     */
    private String elapsed(StackId stackId) {
        List<String> parts = new ArrayList<>();
        Duration delete = deleteDurations.getOrDefault(stackId, deleteDurations.get(StackId.all(stackId.cloud())));
        if (delete != null) {
            parts.add("delete: " + formatDuration(delete.toMillis()));
        }
        if (deployDurations.containsKey(stackId)) {
            parts.add("deploy: " + formatDuration(deployDurations.get(stackId).toMillis()));
        }
        return parts.isEmpty() ? "" : format(" (%s)", String.join(", ", parts));
    }

    /**
     * @return the stack id as used in step names, eg {@code aws_full} for {@code aws-full}
     */
    static String stepName(StackId stackId) {
        return stackId.toString().replace('-', '_');
    }

    /**
     * Fails fast, before the slow {@code tofu apply} even starts, if any configured ACM
     * certificate isn't actually {@code ISSUED}. Without this, a stale/dead cert (e.g. one
     * stuck at {@code VALIDATION_TIMED_OUT}) only surfaces ~20 minutes into the apply, buried
     * in a generic "Load Balancer is not ready yet" error from the ALB controller endlessly
     * retrying {@code CreateListener} against a certificate AWS will never accept. Re-issue a
     * failing one with {@code scripts/aws/issue-cert.sh}.
     * <p>
     * Only AWS has certificate properties for now, so the certificates are ACM's.
     */
    void checkCertificatesIssued(AfonProperty... certificates) throws ManagedProcessException {
        String region = properties.getValue(AWS_REGION);

        List<String> problems = new ArrayList<>();
        for (AfonProperty property : certificates) {
            String certificate = properties.getValue(property);
            if (certificate == null || certificate.isBlank()) {
                return;
            }
            String status = describeCertificateStatus(certificate, region);
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

    private String describeCertificateStatus(String certificate, String region) throws ManagedProcessException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        actions.awsDescribeCertificate(certificate, region, out);
        return out.toString().trim();
    }

    public Step waitForPolarisAndKeycloakIn(AfonCloud cloud) {
        return Step.sequence(format("wait_on_polaris_and_keycloak_for_cloud_%s", cloud),
                format("wait on DNS for Polaris and KeyCloak for cloud `%s`", cloud),
                waitForPolaris(cloud), waitForKeyCloak());
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

    /**
     * Waits for the KeyCloak's DNS record, then for it to serve, ie answer the discovery document of its {@code master}
     * realm: it is deployed without waiting for its pod to be ready, which takes minutes, and what follows, eg configuring the
     * OIDC users, needs it up.
     */
    public Step waitForKeyCloak() {
        long timeout = dnsWaitTimeout();
        Step dns = actions.waitOnDNS(properties.getValue(OIDC_DOMAIN_NAME), timeout);
        String url = KeyCloakConfigurer.urlFor(properties.getValue(OIDC_DOMAIN_NAME), "https")
                + "/realms/master/.well-known/openid-configuration";
        Step answers = actions.waitOnAnswer(url, timeout, KEYCLOAK_POLL_MILLIS);
        return Step.sequence(dns.getName(), dns.getDescription() + ", then for it to answer", dns, answers);
    }

    private static final long KEYCLOAK_POLL_MILLIS = 5_000;

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
        Step[] steps = afonCatalogs.allExcluding("glue").stream()
            .map(catalog -> configurePolarisUsers(cloud, catalog))
            .toArray(Step[]::new);
        return Step.sequence(format("configure_polaris_users_on_all_catalogs_for_cloud_%s", cloud),
                format("configure Polaris users on all catalogs for cloud `%s`", cloud), steps);
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
                return Step.sequence(format("recreate_catalog_%s_for_cloud_%s", azureCatalog, AWS),
                        format("recreate Polaris catalog `%s` for cloud `%s`", azureCatalog, AWS),
                        deleteCatalogFor(AWS, azureCatalog), createCatalogFor(AWS, azureCatalog));
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
        return configureUsersThenRunSpark("Spark writing table",
                cloud, catalog, namespace, polarisTableName, SparkTestWriter.class);
    }

    public @NonNull Step runAwsSparkDeferringToAzurePolarisHostingExternalCatalog() {
        AfonCloud cloud = AWS;
        AfonCatalog catalog = afonCatalogs.azureExternal();
        return configureUsersThenRunSpark(format("Spark defers to %s writing table", AZURE),
                cloud, catalog, polarisNamespaceExternal, polarisTableName, SparkTestWriter.class);
    }


    public @NonNull Step runAwsSparkReadingFrom(AfonCatalog catalog) {
        AfonCloud cloud = AWS;
        return configureUsersThenRunSpark("Spark reading table",
                cloud, catalog, polarisNamespace, polarisTableName, SparkTestReader.class);
    }

    private @NonNull Step configureUsersThenRunSpark(String description, AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName, Class<? extends SparkRunner> sparkRunner) {
        Step spark = runSpark(description, cloud, catalog, namespace, tableName, sparkRunner);
        return Step.sequence(spark.getName(),
                "configure Polaris users then " + spark.getDescription(),
                configurePolarisUsers(cloud, catalog), spark);
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

    /**
     * @param stackId the stack deleted, eg {@code aws-full}, or {@code <cloud>-all} for every stack of the cloud
     */
    public Step deleteComplete(StackId stackId) {
        return new Step(format("delete_complete_for_%s", stepName(stackId)),
                format("Delete complete for stack %s", stackId),
                ()-> logger.info(format("Delete complete for stack %s%s", stackId, elapsed(stackId))));
    }

    public Step deployComplete(StackId stackId) {
        return new Step(format("deploy_complete_for_%s", stepName(stackId)),
                format("Deploy complete for stack %s", stackId),
                ()-> logger.info(format("Deploy complete for stack %s%s", stackId, elapsed(stackId))));
    }

    public Step setupComplete(StackId stackId) {
        return new Step(format("setup_complete_for_%s", stepName(stackId)),
                format("Setup complete for stack %s", stackId),
                ()-> logger.info(format("Setup complete for stack %s%s", stackId, elapsed(stackId))));
    }

}
