package health.emrys.afon.ops.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Checks of the synthesised plan of a stack, {@code cdk.tf.json} in its stack dir, against what is already in the cloud,
 * made before the stack is deployed, so that a deploy that would leave the cloud in a state that cannot be cleaned up is
 * refused before it creates anything.
 */
public class PlanGuards {

    private static final Logger logger = getLogger(PlanGuards.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Checks, before deploying an AWS stack that has no VPC in its tofu state, ie a first deploy, that no VPC in the
     * cloud already has the Name tag of the stack's VPC.  Such a VPC is left over from an earlier deploy whose state was
     * lost or deleted, or whose destroy failed; creating another with the same name makes the name ambiguous, which breaks
     * the name-based cleanup of the stack, and leaves a VPC that nothing destroys.
     * Does nothing if the VPC name or region cannot be read from the plan, or the {@code aws} CLI cannot be run, as then
     * nothing can be checked.
     *
     * @throws IllegalStateException naming the VPCs found
     */
    public void checkNoOrphanAwsVpc(Path stackDir, Path tfState) {
        Path config = stackDir.resolve("cdk.tf.json");
        if (!Files.exists(config) || vpcInState(tfState)) {
            return;
        }
        String name;
        String region;
        try {
            JsonNode plan = MAPPER.readTree(config.toFile());
            name   = plan.path("resource").path("aws_vpc").elements().next().path("tags").path("Name").asText("");
            region = plan.path("provider").path("aws").path(0).path("region").asText("");
        } catch (IOException | NoSuchElementException e) {
            logger.warn("Could not read the VPC name from {}, so not checking for an existing VPC of the stack", config, e);
            return;
        }
        if (name.isEmpty()) {
            return;
        }
        List<String> vpcs = vpcIdsNamed(name, region);
        if (vpcs.isEmpty()) {
            return;
        }
        throw new IllegalStateException(String.format("""
            The stack has no VPC in its tofu state %s, but the cloud already has %d VPC(s) named '%s':
                %s
            They are left over from an earlier deploy whose tofu state was lost or deleted, or whose destroy failed.
            Deploying would create another VPC with the same name, which breaks the cleanup of the stack by name.
            Delete them first, or if one is deployed from this stack, restore its tofu state.
            """, tfState, vpcs.size(), name, String.join("\n    ", vpcs)));
    }

    private boolean vpcInState(Path tfState) {
        if (!Files.exists(tfState)) {
            return false;
        }
        try {
            for (JsonNode resource : MAPPER.readTree(tfState.toFile()).path("resources")) {
                if ("aws_vpc".equals(resource.path("type").asText()) && !resource.path("instances").isEmpty()) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read the tofu state {}", tfState, e);
        }
        return false;
    }

    /**
     * @return the ids of the VPCs with the given Name tag, or none if the {@code aws} CLI cannot be run
     */
    protected List<String> vpcIdsNamed(String name, String region) {
        List<String> command = new ArrayList<>(List.of("aws", "ec2", "describe-vpcs", "--filters", "Name=tag:Name,Values=" + name,
                                                       "--query", "Vpcs[].VpcId", "--output", "text", "--no-cli-pager"));
        if (!region.isEmpty()) {
            command.addAll(List.of("--region", region));
        }
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0 || output.isEmpty()) {
                return List.of();
            }
            return List.of(output.split("\\s+"));
        } catch (IOException e) {
            logger.warn("Could not run the aws CLI to check for an existing VPC named {}", name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }
}
