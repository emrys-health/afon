package health.emrys.afon.cdk;

import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

public abstract class AbstractCloudProvider extends Construct implements CloudProvider {

    private final @NonNull String id;

    public AbstractCloudProvider(@NonNull Construct scope, @NonNull String id) {
        super(scope, id);
        this.id = id;
    }

    public String getId() {
        return id;
    }

    protected abstract String getBase64CertData();

    protected abstract String getClusterEndpoint();

}
