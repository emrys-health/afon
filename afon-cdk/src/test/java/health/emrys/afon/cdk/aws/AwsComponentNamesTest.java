package health.emrys.afon.cdk.aws;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsComponentNamesTest {

    private static final String TEMPLATES_CONFIG_DIR = "../templates/config";
    private static final Path   AWS_SOURCE_DIR       = Path.of("src/main/java/health/emrys/afon/cdk/aws");

    @Test
    void canNameComponentsFromClusterNameQualifiedWithStackType() {
        AwsComponentNames names = new AwsComponentNames(properties("emrys-cluster"), new StackId(AWS, "keycloak"));

        assertThat(names.getCluster()).isEqualTo("keycloak-emrys-cluster");
        assertThat(names.getRegion()).isEqualTo("eu-west-2");
        assertThat(names.getVpcId()).isEqualTo("cdktf-eks-vpc-keycloak-emrys-cluster");
        assertThat(names.getInternetGatewayName()).isEqualTo("cdktf-eks-igw-keycloak-emrys-cluster");
        assertThat(names.getPublicSubnetName(1)).isEqualTo("cdktf-eks-public-1-keycloak-emrys-cluster");
        assertThat(names.getPrivateSubnetName(2)).isEqualTo("cdktf-eks-private-2-keycloak-emrys-cluster");
        assertThat(names.getPublicRouteTableName()).isEqualTo("cdktf-eks-public-rt-keycloak-emrys-cluster");
        assertThat(names.getPrivateRouteTableName()).isEqualTo("cdktf-eks-private-rt-keycloak-emrys-cluster");
        assertThat(names.getNatGatewayName()).isEqualTo("cdktf-eks-nat-keycloak-emrys-cluster");
        assertThat(names.getEgressIpName()).isEqualTo("cdktf-eks-egress-ip-keycloak-emrys-cluster");
        assertThat(names.getOidcProviderName()).isEqualTo("keycloak-emrys-cluster-oidc-provider");
        assertThat(names.getSecret()).isEqualTo("cdktf-app-config-secret-keycloak-emrys-cluster");
        assertThat(names.getAccessPolicyName()).isEqualTo("EKS-Secret-Access-Policy-keycloak-emrys-cluster");
        assertThat(names.getClusterRoleName()).isEqualTo("cdktf-eks-cluster-role-keycloak-emrys-cluster");
        assertThat(names.getNodeGroupRoleName()).isEqualTo("cdktf-eks-nodegroup-role-keycloak-emrys-cluster");
        assertThat(names.getLoadBalancerRoleName()).isEqualTo("AmazonEKSLoadBalancerControllerRolekeycloak-emrys-cluster");
        assertThat(names.getEbsCsiDriverRoleName()).isEqualTo("cdktf-eks-ebs-csi-role-keycloak-emrys-cluster");
    }

    @Test
    void namesComponentsOfEachStackTypeApart() {
        AfonPropertyProvider properties = properties("emrys-cluster");
        List<String> vpcs = Stream.of("keycloak", "polaris", "full")
                                  .map(type -> new AwsComponentNames(properties, new StackId(AWS, type)).getVpcId())
                                  .toList();
        assertThat(vpcs).doesNotHaveDuplicates();
    }

    @Test
    void cannotNameComponentsWithRoleNameTooLongForIam() {
        // 35 chars of "AmazonEKSLoadBalancerControllerRole" + 9 of "keycloak-" + 21 = 65 > 64
        String clusterName = "c".repeat(21);
        assertThatThrownBy(() -> new AwsComponentNames(properties(clusterName), new StackId(AWS, "keycloak")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AmazonEKSLoadBalancerControllerRolekeycloak-" + clusterName)
            .hasMessageContaining(CLUSTER_NAME.getName());
        // one char less fits
        new AwsComponentNames(properties("c".repeat(20)), new StackId(AWS, "keycloak"));
    }

    @Test
    void cannotNameComponentsOfNonAwsStack() {
        assertThatThrownBy(() -> new AwsComponentNames(properties("emrys-cluster"), new StackId(AZURE, "polaris")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void awsCodeNamesComponentsOnlyViaComponentNames() throws IOException {
        try (Stream<Path> sources = Files.list(AWS_SOURCE_DIR)) {
            List<String> readingClusterName = sources
                .filter(source -> !source.endsWith("AwsComponentNames.java"))
                .filter(source -> read(source).contains("CLUSTER_NAME)"))
                .map(source -> source.getFileName().toString())
                .toList();
            assertThat(readingClusterName).isEmpty();
        }
    }

    private static AfonPropertyProvider properties(String clusterName) {
        AfonPropertyProvider properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        properties.setValue(CLUSTER_NAME, clusterName);
        properties.setValue(AWS_REGION, "eu-west-2");
        return properties;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
