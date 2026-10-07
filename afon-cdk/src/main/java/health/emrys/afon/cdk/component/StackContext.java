package health.emrys.afon.cdk.component;

import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The context that a {@link StackComponent} is deployed with: the stack that holds its resources,
 * the platform it is deployed to and the config, as well as the components already deployed, which it may depend on.
 */
public class StackContext {

    private final TerraformStack                              stack;
    private final StackId                                     stackId;
    private final CloudPlatform                               platform;
    private final AfonPropertyProvider                        properties;
    private final Map<Class<? extends StackComponent>, StackComponent> deployed = new LinkedHashMap<>();

    public StackContext(TerraformStack stack, StackId stackId, CloudPlatform platform, AfonPropertyProvider properties) {
        this.stack      = stack;
        this.stackId    = stackId;
        this.platform   = platform;
        this.properties = properties;
    }

    public TerraformStack stack() {
        return stack;
    }

    public StackId stackId() {
        return stackId;
    }

    public CloudPlatform platform() {
        return platform;
    }

    public AfonPropertyProvider properties() {
        return properties;
    }

    /**
     * Deploys the component, which is then found by those deployed after it.  A component is deployed once.
     */
    public <T extends StackComponent> T deploy(T component) {
        if (deployed.containsKey(component.getClass())) {
            throw new IllegalStateException(component.getClass().getSimpleName() + " is already deployed to " + stackId);
        }
        component.deploy(this);
        deployed.put(component.getClass(), component);
        return component;
    }

    /**
     * @return the component of the type if it was deployed, eg to depend on it if the stack has it
     */
    public <T extends StackComponent> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(deployed.get(type)));
    }

    /**
     * @return the component of the type
     * @throws IllegalStateException if it was not deployed before, eg because it is not part of the stack
     */
    public <T extends StackComponent> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
            type.getSimpleName() + " is required but not deployed to " + stackId));
    }
}
