package health.emrys.afon.ops.synth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resources of a stack that are still in the cloud are looked for by the names in its plan, with the CLI of its cloud.
 */
class CloudLeftoversTest {

    private static final String AZURE_PLAN = """
        {"provider": {"azurerm": [{"subscription_id": "sub-1"}]},
         "data": {"azurerm_resource_group": {"rg_1": {"name": "Test-DataFabric"}}},
         "resource": {
           "azurerm_kubernetes_cluster": {"aks_1": {"name": "full-emrys-cluster", "resource_group_name": "${data.azurerm_resource_group.rg_1.name}"}},
           "azurerm_virtual_network": {"vnet_1": {"name": "full-aks-vnet", "resource_group_name": "${data.azurerm_resource_group.rg_1.name}"}},
           "azurerm_user_assigned_identity": {"id_1": {"name": "full-aks-identity", "resource_group_name": "${data.azurerm_resource_group.rg_1.name}"}}}}
        """;

    private static final String AWS_PLAN = """
        {"provider": {"aws": [{"region": "eu-west-2"}]},
         "resource": {
           "aws_eks_cluster": {"c": {"name": "full-emrys-cluster"}},
           "aws_nat_gateway": {"n": {"tags": {"Name": "cdktf-eks-nat-full-emrys-cluster"}}},
           "aws_vpc": {"v": {"tags": {"Name": "cdktf-eks-vpc-full-emrys-cluster"}}},
           "aws_ebs_volume": {"retained_gitea_volume": {"tags": {"Name": "gitea-full-emrys-cluster"}}}}}
        """;

    private static final String GCP_PLAN = """
        {"provider": {"google": [{"project": "proj-1"}]},
         "resource": {
           "google_container_cluster": {"g": {"name": "emrys-cluster"}},
           "google_compute_network": {"n": {"name": "gke-private-vpc-emrys-cluster"}}}}
        """;

    @TempDir
    Path dir;

    /** What the cloud has, by the name looked for, and the commands run. */
    private Map<String, List<String>> inCloud = Map.of();
    private final List<List<String>> commands = new ArrayList<>();

    private final CloudLeftovers leftovers = new CloudLeftovers() {
        @Override
        protected List<String> lines(List<String> command) {
            commands.add(command);
            return inCloud.entrySet().stream().filter(e -> command.stream().anyMatch(arg -> arg.contains(e.getKey())))
                          .flatMap(e -> e.getValue().stream()).toList();
        }
    };

    private Path stackDir(String plan) throws IOException {
        Files.writeString(dir.resolve("cdk.tf.json"), plan);
        return dir;
    }

    @Test
    void findsTheAzureResourcesStillInTheResourceGroupOfThePlan() throws Exception {
        inCloud = Map.of("full-emrys-cluster", List.of("/subscriptions/s/aks"), "full-aks-vnet", List.of("/subscriptions/s/vnet"));

        assertThat(leftovers.in(stackDir(AZURE_PLAN))).containsExactlyInAnyOrder(
            "AKS cluster full-emrys-cluster in resource group Test-DataFabric: /subscriptions/s/aks",
            "virtual network full-aks-vnet in resource group Test-DataFabric: /subscriptions/s/vnet");
        assertThat(commands).hasSize(3).allSatisfy(command -> assertThat(command)
            .containsSubsequence("az", "resource", "list", "--resource-group", "Test-DataFabric")
            .containsSubsequence("--subscription", "sub-1"));
    }

    @Test
    void findsTheAwsClusterNatAndVpcButNotTheRetainedVolume() throws Exception {
        inCloud = Map.of("list-clusters", List.of("full-emrys-cluster"), "cdktf-eks-nat", List.of("nat-1"),
                         "cdktf-eks-vpc", List.of("vpc-1"), "gitea-full", List.of("vol-1"));

        assertThat(leftovers.in(stackDir(AWS_PLAN))).containsExactlyInAnyOrder(
            "EKS cluster full-emrys-cluster: full-emrys-cluster",
            "NAT gateway named cdktf-eks-nat-full-emrys-cluster: nat-1",
            "VPC named cdktf-eks-vpc-full-emrys-cluster: vpc-1");
        assertThat(commands).hasSize(3).allSatisfy(command -> assertThat(command).containsSubsequence("--region", "eu-west-2"));
    }

    @Test
    void findsTheGcpClusterAndNetworkOfTheProject() throws Exception {
        inCloud = Map.of("name=emrys-cluster", List.of("emrys-cluster"));

        assertThat(leftovers.in(stackDir(GCP_PLAN))).containsExactly("GKE cluster emrys-cluster in project proj-1: emrys-cluster");
        assertThat(commands).hasSize(2);
    }

    @Test
    void findsNothingWhenTheCloudHasNone() throws Exception {
        assertThat(leftovers.in(stackDir(AZURE_PLAN))).isEmpty();
        assertThat(leftovers.in(stackDir(AWS_PLAN))).isEmpty();
        assertThat(leftovers.in(stackDir(GCP_PLAN))).isEmpty();
    }

    @Test
    void doesNotLookForWhatThePlanDoesNotState() throws Exception {
        String plan = """
            {"resource": {"azurerm_kubernetes_cluster": {"a": {"name": "${var.name}", "resource_group_name": "rg"}},
                          "azurerm_virtual_network": {"b": {"name": "vnet", "resource_group_name": "${data.azurerm_resource_group.gone.name}"}},
                          "google_container_cluster": {"g": {"name": "emrys-cluster"}}}}
            """;

        assertThat(leftovers.in(stackDir(plan))).isEmpty();
        assertThat(commands).isEmpty();
    }

    @Test
    void findsNothingWithoutAPlan() {
        assertThat(leftovers.in(dir)).isEmpty();
        assertThat(commands).isEmpty();
    }
}
