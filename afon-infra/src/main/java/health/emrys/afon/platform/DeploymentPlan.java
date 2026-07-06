package health.emrys.afon.platform;

import health.emrys.afon.config.deployment.AfonDeploymentConfig;
import health.emrys.afon.demo.Step;

import java.util.List;

/**
 * The ordered, executable interpretation of an {@code afon-deployment.yaml}:
 * what {@link DeploymentPlanner} emits and {@code StepRunner} executes.
 */
public record DeploymentPlan(AfonDeploymentConfig config, List<Step> steps) {

    public List<String> stepNames() {
        return steps.stream().map(Step::name).toList();
    }

    /** Markdown rendering, in the spirit of the {@code Document*Main} classes. */
    public String describe() {
        StringBuilder md = new StringBuilder();
        md.append("# Deployment plan: ").append(config.deployment().name()).append("\n\n");
        md.append("| # | Step | Description |\n");
        md.append("|---|------|-------------|\n");
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            md.append("| ").append(i + 1)
              .append(" | `").append(step.name())
              .append("` | ").append(step.description())
              .append(" |\n");
        }
        return md.toString();
    }
}
