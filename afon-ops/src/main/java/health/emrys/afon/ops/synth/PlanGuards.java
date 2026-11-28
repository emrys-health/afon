package health.emrys.afon.ops.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Checks of the synthesised plan of a stack, {@code cdk.tf.json} in its stack dir, against what is already in the cloud,
 * made before the stack is deployed, so that a deploy that would leave the cloud in a state that cannot be cleaned up is
 * refused before it creates anything.
 */
public class PlanGuards {

    private static final Logger logger = getLogger(PlanGuards.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloudLeftovers leftovers;

    /** @param leftovers what looks in the cloud for what is left of a stack, replaced to test without the cloud CLIs */
    protected PlanGuards(CloudLeftovers leftovers) {
        this.leftovers = leftovers;
    }

    public PlanGuards() {
        this(new CloudLeftovers());
    }

    /**
     * Checks, before deploying a stack that has nothing in its tofu state, ie a first deploy, that the cloud has none of the stack's
     * resources: a cluster or network that is there is left over from an earlier deploy whose state was lost or deleted, or whose
     * destroy failed, and creating another fails with {@code already exists} part way through the apply, or, for a VPC, makes its name
     * ambiguous, which breaks the name-based cleanup of the stack.
     * Does nothing if the state has resources, which tofu then knows, or the plan or the cloud CLI cannot be read or run, as then
     * nothing can be checked.
     *
     * @throws IllegalStateException naming the resources found
     */
    public void checkNoneLeftBeforeDeploy(Path stackDir, Path tfState) {
        if (stateHasResources(tfState)) {
            return;
        }
        List<String> left = leftovers.in(stackDir);
        if (left.isEmpty()) {
            return;
        }
        throw new IllegalStateException(String.format("""
            The stack has nothing in its tofu state %s, but the cloud already has %d resource(s) of it:
                %s
            They are left over from an earlier deploy whose tofu state was lost or deleted, or whose destroy failed.
            Deploying would fail, with 'already exists', or leave resources that nothing destroys.
            Delete them first, or if they are deployed from this stack, restore its tofu state.
            """, tfState, left.size(), String.join("\n    ", left)));
    }

    /**
     * Checks, after {@code tofu destroy} and before the tofu state and the stack dir are deleted, that the cloud has none of the stack's
     * resources, whatever the state says: a state that has lost them, eg after a delete that was interrupted and run again, is empty,
     * so the check of the state finds nothing, and deleting it leaves the stack in the cloud, untracked, to fail the next deploy.
     * Does nothing if the plan or the cloud CLI cannot be read or run, as then nothing can be checked.
     *
     * @throws IllegalStateException naming the resources found
     */
    public void checkNoneLeftAfterDestroy(Path stackDir, Path tfState) {
        List<String> left = leftovers.in(stackDir);
        if (left.isEmpty()) {
            return;
        }
        throw new IllegalStateException(String.format("""
            tofu destroy finished, but the cloud still has %d resource(s) of the stack:
                %s
            Neither the tofu state %s nor the stack dir %s is deleted, as that would leave them in the cloud with nothing to destroy them.
            Run the delete again. If they are still there, find out why, eg a cluster left in a Failed state by an upgrade,
            and delete them in the cloud, with its CLI or the cleanup script of the cloud.
            """, left.size(), String.join("\n    ", left), tfState, stackDir));
    }

    private boolean stateHasResources(Path tfState) {
        if (!Files.exists(tfState)) {
            return false;
        }
        try {
            for (JsonNode resource : MAPPER.readTree(tfState.toFile()).path("resources")) {
                if ("managed".equals(resource.path("mode").asText("managed")) && !resource.path("instances").isEmpty()) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read the tofu state {}, so taking it to have resources", tfState, e);
            return true;
        }
        return false;
    }
}
