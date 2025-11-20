package health.emrys.afon.cdk;

import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

abstract class AbstractConstruct extends Construct implements CloudProvider {

    private KubernetesProvider kubernetesProvider;

    public AbstractConstruct(@NotNull Construct scope, @NotNull String id) {
        super(scope, id);
    }

    public synchronized KubernetesProvider configureK8s() {
        if (kubernetesProvider == null) {
            String base64CertData = getBase64CertData();
            String encodedCert    = com.hashicorp.cdktf.Fn.base64decode(base64CertData);
            kubernetesProvider    = KubernetesProvider.Builder.create(this, "kubernetes")
                                                           .host(getClusterEndpoint())
                                                           .clusterCaCertificate(encodedCert)
                                                           .token(getToken()) // Dynamically generated token
                                                           .build();
        }
        return kubernetesProvider;
    }


    protected abstract String getBase64CertData();

    protected abstract String getClusterEndpoint();

    protected abstract String getToken();

}
