package health.emrys.afon.ops.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Looks in the cloud, with its CLI, for the resources of a stack that are still deployed, rather than in its tofu state, which
 * may have lost them, eg after a delete that was interrupted and run again.
 * <p>
 * The resources looked for are those that make a stack: its managed cluster and its network, and the identity of the cluster on
 * Azure, found by the names that the synthesised plan of the stack, {@code cdk.tf.json} in its stack dir, gives them.  Others,
 * such as the volume that outlives the stack, are not looked for, so they never count as left.
 * What cannot be told is not reported: a name that the plan does not state, or a CLI that cannot be run or is not logged in.
 */
public class CloudLeftovers {

    private static final Logger logger = getLogger(CloudLeftovers.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What to look for, as it is reported if found, and the command that lists what is found, one per line. */
    record Probe(String what, List<String> command) {
    }

    /**
     * @return the resources of the stack that are in the cloud, each as a line naming what it is and its ids, or none if the plan cannot
     * be read or nothing is found
     */
    public List<String> in(Path stackDir) {
        Path config = stackDir.resolve("cdk.tf.json");
        if (!Files.exists(config)) {
            logger.info("Not looking in the cloud for what is left of the stack: no plan {}", config);
            return List.of();
        }
        JsonNode plan;
        try {
            plan = MAPPER.readTree(config.toFile());
        } catch (IOException e) {
            logger.warn("Could not read {}, so not looking in the cloud for what is left of the stack", config, e);
            return List.of();
        }
        List<Probe> probes = probes(plan);
        if (probes.isEmpty()) {
            logger.info("Not looking in the cloud for what is left of the stack: its plan {} names no cluster or network to look for", config);
            return List.of();
        }
        logger.info("Looking in the cloud for what may be left of the stack: {}", probes.stream().map(Probe::what).toList());
        List<String> found = new ArrayList<>();
        for (Probe probe : probes) {
            List<String> ids = lines(probe.command());
            if (!ids.isEmpty()) {
                found.add(probe.what() + ": " + String.join(", ", ids));
            }
        }
        if (found.isEmpty()) {
            logger.info("Nothing of the stack is left in the cloud");
        }
        return found;
    }

    static List<Probe> probes(JsonNode plan) {
        List<Probe> probes = new ArrayList<>();
        azure(plan, probes);
        aws(plan, probes);
        gcp(plan, probes);
        return probes;
    }

    private static final Map<String, String[]> AZURE_TYPES = Map.of(
        "azurerm_kubernetes_cluster", new String[]{"Microsoft.ContainerService/managedClusters", "AKS cluster"},
        "azurerm_virtual_network", new String[]{"Microsoft.Network/virtualNetworks", "virtual network"},
        "azurerm_user_assigned_identity", new String[]{"Microsoft.ManagedIdentity/userAssignedIdentities", "managed identity"});

    private static void azure(JsonNode plan, List<Probe> probes) {
        String subscription = literal(plan.path("provider").path("azurerm").path(0).path("subscription_id"));
        AZURE_TYPES.forEach((type, kind) -> plan.path("resource").path(type).forEach(resource -> {
            String name  = literal(resource.path("name"));
            String group = azureResourceGroup(plan, resource.path("resource_group_name"));
            if (name.isEmpty() || group.isEmpty()) {
                return;
            }
            List<String> command = new ArrayList<>(List.of("az", "resource", "list", "--resource-group", group, "--resource-type", kind[0],
                                                           "--name", name, "--query", "[].id", "--output", "tsv"));
            if (!subscription.isEmpty()) {
                command.addAll(List.of("--subscription", subscription));
            }
            probes.add(new Probe(String.format("%s %s in resource group %s", kind[1], name, group), command));
        }));
    }

    /** @return the resource group, as a name or as a reference to the {@code azurerm_resource_group} data source of the plan */
    private static String azureResourceGroup(JsonNode plan, JsonNode value) {
        String text = value.asText("");
        String prefix = "${data.azurerm_resource_group.";
        if (text.startsWith(prefix) && text.endsWith(".name}")) {
            String id = text.substring(prefix.length(), text.length() - ".name}".length());
            return literal(plan.path("data").path("azurerm_resource_group").path(id).path("name"));
        }
        return literal(value);
    }

    private static void aws(JsonNode plan, List<Probe> probes) {
        String region = literal(plan.path("provider").path("aws").path(0).path("region"));
        plan.path("resource").path("aws_eks_cluster").forEach(resource -> {
            String name = literal(resource.path("name"));
            if (!name.isEmpty()) {
                probes.add(new Probe("EKS cluster " + name,
                    withRegion(List.of("aws", "eks", "list-clusters", "--query", "clusters[?@=='" + name + "']", "--output", "text",
                                       "--no-cli-pager"), region)));
            }
        });
        plan.path("resource").path("aws_nat_gateway").forEach(resource -> {
            String name = literal(resource.path("tags").path("Name"));
            if (!name.isEmpty()) {
                probes.add(new Probe("NAT gateway named " + name,
                    withRegion(List.of("aws", "ec2", "describe-nat-gateways", "--filter", "Name=tag:Name,Values=" + name,
                                       "Name=state,Values=pending,available", "--query", "NatGateways[].NatGatewayId", "--output", "text",
                                       "--no-cli-pager"), region)));
            }
        });
        plan.path("resource").path("aws_vpc").forEach(resource -> {
            String name = literal(resource.path("tags").path("Name"));
            if (!name.isEmpty()) {
                probes.add(new Probe("VPC named " + name,
                    withRegion(List.of("aws", "ec2", "describe-vpcs", "--filters", "Name=tag:Name,Values=" + name, "--query",
                                       "Vpcs[].VpcId", "--output", "text", "--no-cli-pager"), region)));
            }
        });
    }

    private static List<String> withRegion(List<String> command, String region) {
        if (region.isEmpty()) {
            return command;
        }
        List<String> withRegion = new ArrayList<>(command);
        withRegion.addAll(List.of("--region", region));
        return withRegion;
    }

    private static void gcp(JsonNode plan, List<Probe> probes) {
        String project = literal(plan.path("provider").path("google").path(0).path("project"));
        if (project.isEmpty()) {
            return;
        }
        plan.path("resource").path("google_container_cluster").forEach(resource -> {
            String name = literal(resource.path("name"));
            if (!name.isEmpty()) {
                probes.add(new Probe("GKE cluster " + name + " in project " + project,
                    List.of("gcloud", "container", "clusters", "list", "--project", project, "--filter", "name=" + name,
                            "--format", "value(name)")));
            }
        });
        plan.path("resource").path("google_compute_network").forEach(resource -> {
            String name = literal(resource.path("name"));
            if (!name.isEmpty()) {
                probes.add(new Probe("VPC network " + name + " in project " + project,
                    List.of("gcloud", "compute", "networks", "list", "--project", project, "--filter", "name=" + name,
                            "--format", "value(name)")));
            }
        });
    }

    /** @return the text of a value that the plan states, rather than one that tofu works out, ie an interpolation, which is empty */
    private static String literal(JsonNode value) {
        String text = value.asText("");
        return text.contains("${") ? "" : text;
    }

    /**
     * @return the lines that the command prints, or none if it fails or cannot be run, as then nothing can be told
     */
    protected List<String> lines(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0) {
                logger.warn("{} failed, so not telling whether that is left of the stack", command.get(0));
                return List.of();
            }
            return Arrays.stream(output.split("\\s*\\R\\s*|\\s+")).filter(line -> !line.isBlank() && !line.equals("None")).toList();
        } catch (IOException e) {
            logger.warn("Could not run {}, so not telling whether that is left of the stack", command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }
}
