package health.emrys.afon.ops.smoke.config;

import health.emrys.afon.cdk.configs.WhitelistConfig;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.FilePropertyProvider;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static health.emrys.afon.cdk.configs.SparkWorkloads.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.config.AfonProperty.*;
import static java.lang.String.format;

public class SmokeConfig {

    public static final String KUBERNETES_NAMESPACE = "default";
    public static final String SPARK_CONNECTOR_POD = SPARK_CONNECTOR_NAME + "-0-driver";
    public static final long PROGRESS_INTERVAL_SECONDS = 10;
    public static final long CALL_TIMEOUT_SECONDS = 30;
    public static final AfonCloud DEFAULT_TRE_CLOUD = AfonCloud.AWS;

    private final AfonPropertyProvider properties;
    private final WhitelistConfig whitelist;

    /**
     * This returns the exact properties (and only those properties) used in a real environment that has been deployed.
     * It should otherwise be untainted.
     */
    public static AfonPropertyProvider smokeTestProperties() {
        return new FilePropertyProvider();
    }

    /**
     * @return whether the config is of a project stack's TRE, which names the claim that its tokens carry the project stack in: set, and not blank
     */
    public static boolean isProjectStack(AfonPropertyProvider properties) {
        return properties.hasProperty(OIDC_PROJECT_CLAIM) && !properties.getValue(OIDC_PROJECT_CLAIM, "").isBlank();
    }

    public SmokeConfig(AfonPropertyProvider properties) {
        this(properties, new WhitelistConfig(properties));
    }

    public SmokeConfig(AfonPropertyProvider properties, WhitelistConfig whitelist) {
        this.properties = properties;
        this.whitelist = whitelist;
    }

    public SmokeConfig() {
        this(smokeTestProperties());
    }

    public AfonPropertyProvider getProperties() {
        return properties;
    }

    /**
     * @return the cloud of the stack hosting the TRE, from {@code TRE_CLOUD}, or {@link #DEFAULT_TRE_CLOUD} if it is
     * not set or empty
     * @throws IllegalArgumentException if {@code TRE_CLOUD} is not one of the clouds
     */
    public AfonCloud cloud() {
        String cloud = properties.getValue(TRE_CLOUD, "").trim();
        if (cloud.isEmpty()) {
            return DEFAULT_TRE_CLOUD;
        }
        try {
            return AfonCloud.valueOf(cloud.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid TRE_CLOUD '" + cloud + "', which should be one of aws, azure or gcp", e);
        }
    }

    /**
     * @return the allowed domains that the TRE cloud's pods must be able to reach: the common ones of {@code whitelist.yaml}
     * and those of the TRE cloud's section, not those of the other clouds, which only resolve while the stack of that cloud
     * is deployed, so are not the TRE cloud's to reach.
     */
    public List<String> allowedDomains() {
        return whitelist.domains(cloud());
    }

    /**
     * Where the ingress controller runs in the cluster, as nginx does on Azure, a caller outside of the allowed CIDRs is
     * answered by the controller itself, with its own 403 page, rather than being dropped as by the AWS ALB, so no call to
     * Polaris is made.  Polaris itself, which is not nginx, never answers so.
     *
     * @return whether the response is that of the ingress controller rejecting the caller
     */
    public boolean rejectedByIngress(int httpCode, String body) {
        return httpCode == 403 && body != null && body.toLowerCase(Locale.ROOT).contains("<center>nginx</center>");
    }

    /**
     * @return what to do for Polaris to only be reachable from inside the cluster, which the TRE cloud's cluster fails to
     * be if it can be called from outside, eg from where the test runs
     */
    public String polarisRestrictionAdvice() {
        AfonCloud cloud = cloud();
        return switch (cloud) {
            case AWS -> format("""
                Run this to make Polaris white list the TRE's IP address:

                kubectl annotate ingress polaris-ingress -n default --overwrite alb.ingress.kubernetes.io/inbound-cidrs=%s/32

                The EGRESS_IP of this cluster is configured in config/afon-aws.properties.

                If you want to add this machines IP address to that whitelist (danger, Will Robinson), get it via:

                curl -s https://ifconfig.me

                and add it to %s (which are CSVs)

                """, properties.getValue(AWS_EGRESS_IP, "undefined"), AWS_EGRESS_IP);
            case AZURE -> """
                Set AZURE_POLARIS_ALLOWED_CIDRS in config/afon-azure.properties and redeploy, to make the ingress of Polaris
                admit only those callers, or run this to restrict it now, with the cluster's pod CIDR so that the pods still
                reach it:

                kubectl annotate ingress polaris-ingress -n default --overwrite nginx.ingress.kubernetes.io/whitelist-source-range=<cidr>,<cidr>

                The callers' IPs are only seen by the ingress controller if its service has externalTrafficPolicy Local, which the redeploy sets.
                """;
            case GCP -> """
                Set GCP_POLARIS_ALLOWED_CIDRS in config/afon-gcp.properties and redeploy, to make the ingress of Polaris
                admit only those callers, or run this to restrict it now, with the cluster's pod range and node subnet so that
                the pods still reach it:

                kubectl annotate ingress polaris-ingress -n default --overwrite nginx.ingress.kubernetes.io/whitelist-source-range=<cidr>,<cidr>

                The callers' IPs are only seen by the ingress controller if its service has externalTrafficPolicy Local, which the redeploy sets.
                afon-cli.sh kube-polaris-whitelist gcp set does both.
                """;
            default -> "Polaris can only be restricted on AWS, Azure and GCP, not on " + cloud;
        };
    }

    /**
     * @return the interval, in seconds, at which to log that a call is still waiting for a response
     */
    public long progressIntervalSeconds() {
        return PROGRESS_INTERVAL_SECONDS;
    }

    /**
     * @return the time, in seconds, after which to give up waiting for the response of a call
     */
    public long callTimeoutSeconds() {
        return CALL_TIMEOUT_SECONDS;
    }


    /**
     * @return the catalogs that the project's Data Providers serve, which a TRE reads and the smoke tests query as a member.  They are read from the config of every cloud, whichever cloud
     * the run is of: a Data Provider may be on another cloud than the TRE, eg one on Azure read by a TRE on AWS, and a run of the TRE's cloud, as the commands of a project are, loads only
     * that cloud's catalogs, so that these would be hidden from it
     */
    public static List<AfonCatalog> dataProviderCatalogs() {
        AfonPropertyProvider everyCloud = new FilePropertyProvider(ConfigUtils.configDir(), List.of("afon", "secret", "catalogs"), true);
        return new AfonCatalogs(everyCloud).bySource(AfonCatalogs.Source.DATA_PROVIDER);
    }

    /**
     * @return the OIDC token of the user of the smoke tests, from the KeyCloak of the cloud under test, without which the Spark Connect server refuses a session: that of
     * {@code USER_ID}, or, in project mode, of the smoke member, see {@link SmokeUser}
     */
    public static String sparkConnectToken() throws IOException, InterruptedException {
        return SmokeUser.of(smokeTestProperties()).token();
    }
}
