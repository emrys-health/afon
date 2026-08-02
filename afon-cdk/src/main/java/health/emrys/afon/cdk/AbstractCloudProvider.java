package health.emrys.afon.cdk;

import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

public abstract class AbstractCloudProvider extends Construct implements CloudProvider {

    private final @NonNull String             id;
    private                KubernetesProvider kubernetesProvider;

    public AbstractCloudProvider(@NonNull Construct scope, @NonNull String id) {
        super(scope, id);
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public synchronized KubernetesProvider configureK8s() {
        if (kubernetesProvider == null) {
            String base64CertData = getBase64CertData();
            String encodedCert    = com.hashicorp.cdktf.Fn.base64decode(base64CertData);
            kubernetesProvider = KubernetesProvider.Builder
                .create(this, "kubernetes")
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
