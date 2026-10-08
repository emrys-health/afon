package health.emrys.afon.ops.plan;

/**
 * The {@link ComponentPlan} for the infrastructure components, decorating its deployment with a no-op implementation.
 * They are deployed with the rest of the stack, as every component is, and hold no domain name, need no certificate,
 * and have nothing to wait for or configure, eg the network policies or the Spark operator.
 * Declaring a component with this plan in {@link ComponentPlanRegistry} makes it explicit rather than having
 * to exclude the infrastructure components.
 */
public class InfrastructurePlan implements ComponentPlan {
}
