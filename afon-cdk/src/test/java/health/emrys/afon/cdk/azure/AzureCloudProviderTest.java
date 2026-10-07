package health.emrys.afon.cdk.azure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AzureCloudProviderTest {

    @Test
    void tlsSecretNamesMatchIssueCertScript() {
        assertThat(AzureCloudProvider.tlsSecretNames("polaris.example.com"))
            .containsExactly("polaris-example-com-tls-crt", "polaris-example-com-tls-key");
    }

    @Test
    void restoreCommandReadsSecretsFromKeyVault() {
        String command = AzureCloudProvider.restoreCachedCertCommand("my-vault", new String[]{"a-tls-crt", "a-tls-key"}, "polaris-tls");
        assertThat(command).contains("--vault-name 'my-vault' --name 'a-tls-crt'")
                           .contains("--vault-name 'my-vault' --name 'a-tls-key'")
                           .contains("kubectl create secret generic polaris-tls")
                           .contains("kubectl apply --server-side")
                           .contains("kubectl annotate secret polaris-tls")
                           .contains("cert-manager.io/issuer-name=letsencrypt-prod");
    }

}
