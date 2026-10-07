package health.emrys.afon.cdk;

import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

public abstract class AbstractCloudProvider extends Construct implements CloudPlatform {

    private final @NonNull String id;
    private final @NonNull Construct stack;

    public AbstractCloudProvider(@NonNull Construct scope, @NonNull String id) {
        super(scope, id);
        this.id = id;
        this.stack = scope;
    }

    public String getId() {
        return id;
    }

    /**
     * @return the scope of the stack the provider is part of, which holds the resources of what is deployed to it
     */
    protected Construct stack() {
        return stack;
    }

    protected abstract String getBase64CertData();

    protected abstract String getClusterEndpoint();

}
