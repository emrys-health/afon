package health.emrys.afon.platform;

import com.hashicorp.cdktf.App;
import health.emrys.afon.config.deployment.AfonDeploymentConfig;
import health.emrys.afon.config.deployment.CloudSpec;
import health.emrys.afon.config.deployment.DeploymentConfigParser;
import health.emrys.afon.config.deployment.EngineSpec;
import health.emrys.afon.config.deployment.WorkbenchSpec;
import health.emrys.afon.demo.Step;
import health.emrys.afon.demo.StepActions;
import health.emrys.afon.infra.PurgeAndPopulatePolaris;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.platform.Legacy.enumFor;
import static java.lang.String.format;

/**
 * Turns an {@code afon-deployment.yaml} into the ordered {@link Step} list the
 * existing {@code StepRunner} executes (see
 * {@code docs/config_driven_architecture.md} §5.4):
 * <pre>
 * per cloud:   clean → deploy (synth plugins + tofu apply) → waitForDns → catalogs
 * per engine:  runtime steps last (Spark smoke test, Snowflake catalog integrations)
 * </pre>
 * Spark/Trino/the workbench synthesise into their target cloud's stack inside
 * the deploy step; anything an engine must do against the *live* deployment
 * comes from {@link QueryEngineDeployer#runtimeSteps}.
 * <p>
 * Phase 3 constraint: cloud ids are still bound to the legacy stack ids
 * ({@code aws}/{@code azure}/{@code gcp}) — enforced by the provider plugins —
 * because tooling like {@code StepActions#deleteStack} keys state off
 * {@code SupportedCloudsEnum}. Phase 4 frees the ids.
 */
public final class DeploymentPlanner {

    private final PluginRegistry registry;

    public DeploymentPlanner() {
        this(PluginRegistry.standard());
    }

    public DeploymentPlanner(PluginRegistry registry) {
        this.registry = registry;
    }

    public DeploymentPlan plan(Path configFile, Properties properties) throws IOException {
        return plan(new DeploymentConfigParser().parse(configFile), properties, new StepActions());
    }

    public DeploymentPlan plan(AfonDeploymentConfig config, Properties properties, StepActions actions) {
        DeploymentContext ctx   = DeploymentContext.of(config, properties);
        List<Step>        steps = new ArrayList<>();

        for (CloudSpec cloud : config.clouds()) {
            steps.add(cleanStep(cloud, actions));
            steps.add(deployStep(cloud, config, ctx, actions));
            steps.add(actions.waitFor(polarisDomainOf(cloud)));
            steps.add(initializeCatalogsStep(cloud, actions));
        }

        for (EngineSpec engine : config.queryEnginesOrEmpty()) {
            steps.addAll(registry.engineDeployer(engine.type())
                                 .runtimeSteps(actions, runtimePolarisDomainFor(engine, config), engine, ctx));
        }

        return new DeploymentPlan(config, List.copyOf(steps));
    }

    private Step cleanStep(CloudSpec cloud, StepActions actions) {
        return new Step(
            "clean_" + cloud.id(),
            format("clean the local %s state (if it exists)", cloud.id()),
            () -> actions.deleteStack(enumFor(cloud.id())));
    }

    /**
     * One step per cloud: synthesise the cloud plus everything the config
     * layers onto it (in-cluster engines, the workbench), then the two-phase
     * {@code tofu apply} that {@code StepActions} already implements.
     */
    private Step deployStep(CloudSpec cloud,
                            AfonDeploymentConfig config,
                            DeploymentContext ctx,
                            StepActions actions) {
        return new Step(
            "deploy_" + cloud.id(),
            format("synthesise and apply the %s stack (%s)%s",
                   cloud.id(),
                   cloud.provider(),
                   describeLayers(cloud, config)),
            () -> {
                App app = new App();
                synthesise(app, cloud, config, ctx);
                app.synth();
                actions.startManagedKubernetesStack(enumFor(cloud.id()));
            });
    }

    /** Package-private so tests can synth without applying. */
    ProvisionedCloud synthesise(App app,
                                CloudSpec cloud,
                                AfonDeploymentConfig config,
                                DeploymentContext ctx) {
        ProvisionedCloud provisioned = registry.cloudPlugin(cloud.provider()).provision(app, cloud, ctx);
        provisioned.hardenStack();
        for (EngineSpec engine : inClusterEnginesTargeting(cloud.id(), config)) {
            registry.engineDeployer(engine.type()).deploy(provisioned, engine, ctx);
        }
        WorkbenchSpec workbench = config.workbench();
        if (workbench != null && cloud.id().equals(workbench.target())) {
            registry.workbenchDeployer(workbench.type()).deploy(provisioned, workbench, ctx);
        }
        return provisioned;
    }

    private Step initializeCatalogsStep(CloudSpec cloud, StepActions actions) {
        String domain = polarisDomainOf(cloud);
        return new Step(
            "all_catalogs_in_" + cloud.id(),
            "configure all the catalogs in the Polaris at " + domain,
            () -> actions.executeMainOf(
                PurgeAndPopulatePolaris.class,
                new String[]{domain},
                new Properties()));
    }

    private List<EngineSpec> inClusterEnginesTargeting(String cloudId, AfonDeploymentConfig config) {
        return config.queryEnginesOrEmpty().stream()
                     .filter(e -> cloudId.equals(e.target()))
                     .toList();
    }

    /**
     * The Polaris a SaaS engine's runtime steps talk to: its {@code target} if
     * set, else the first declared cloud (matching the demo, where Snowflake
     * reads everything through the AWS Polaris).
     */
    private String runtimePolarisDomainFor(EngineSpec engine, AfonDeploymentConfig config) {
        String targetId = engine.target() != null ? engine.target() : config.clouds().get(0).id();
        return config.clouds().stream()
                     .filter(c -> targetId.equals(c.id()))
                     .findFirst()
                     .map(DeploymentPlanner::polarisDomainOf)
                     .orElseThrow(() -> new IllegalArgumentException(
                         "Engine '" + engine.type() + "' targets unknown cloud '" + targetId + "'"));
    }

    private static String polarisDomainOf(CloudSpec cloud) {
        String domain = cloud.polaris() == null ? null : cloud.polaris().get("domain");
        if (domain == null) {
            throw new IllegalArgumentException(
                "Cloud '" + cloud.id() + "' declares no polaris.domain — required to plan its deployment");
        }
        return domain;
    }

    private String describeLayers(CloudSpec cloud, AfonDeploymentConfig config) {
        List<String> layers = new ArrayList<>();
        inClusterEnginesTargeting(cloud.id(), config).forEach(e -> layers.add(e.type()));
        WorkbenchSpec workbench = config.workbench();
        if (workbench != null && cloud.id().equals(workbench.target())) {
            layers.add(workbench.type() + " workbench");
        }
        return layers.isEmpty() ? "" : " with " + String.join(", ", layers);
    }
}
