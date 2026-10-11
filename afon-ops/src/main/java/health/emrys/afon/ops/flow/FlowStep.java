package health.emrys.afon.ops.flow;

import health.emrys.afon.ops.steps.Step;

/**
 * A step of an item of a flow.  As step names repeat across items, it is identified by {@link #qualifiedName()}, eg
 * {@code 2:deploy_complete_azure_polaris}, which is what a flow resumes from.
 *
 * @param itemIndex the 1-based index of the item in the flow
 */
public record FlowStep(int itemIndex, FlowItem item, Step step) {

    public String qualifiedName() {
        return itemIndex + ":" + step.getName();
    }
}
