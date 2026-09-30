package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hashicorp.cdktf.IAspect;
import com.hashicorp.cdktf.TerraformResource;
import org.jspecify.annotations.NonNull;
import software.constructs.IConstruct;

import java.util.List;

/**
 * Sets {@code KUBECONFIG} on every {@code local-exec} provisioner of a stack, so that each of its kubectl and helm
 * commands, eg the destroy-time cleanups, acts on the stack's own cluster via its own kubeconfig, rather than on
 * whichever cluster the machine's current kubectl context points at, which may be another stack's.
 * <p>
 * It is an aspect, applied to every resource of the stack when it is synthesised, so that no provisioner can miss it,
 * wherever it is created.
 */
public class KubeConfigAspect implements IAspect {

    public static final String KUBECONFIG = "KUBECONFIG";

    /**
     * Reads the synthesised resource, whose lists are Scala lists when jackson-module-scala is on the classpath,
     * as jsii then registers it, hence registering the same modules.
     */
    private static final ObjectMapper SYNTHESISED = new ObjectMapper().findAndRegisterModules();
    private static final ObjectMapper PLAIN       = new ObjectMapper();

    private static final String LOCAL_EXEC  = "local-exec";
    private static final String PROVISIONER = "provisioner";
    private static final String ENVIRONMENT = "environment";

    private final String kubeConfigPath;

    public KubeConfigAspect(@NonNull String kubeConfigPath) {
        this.kubeConfigPath = kubeConfigPath;
    }

    /**
     * Rewrites the provisioners of the resource, as synthesised, with {@code KUBECONFIG} added to the environment of
     * each {@code local-exec} one.  They are rewritten as an override of the synthesised {@code provisioner} block,
     * because a provisioner read back from the resource is a jsii struct typed as the first of the provisioner types,
     * whose fields cannot be read.
     */
    @Override
    public void visit(@NonNull IConstruct node) {
        // no provisioners, or already rewritten, as they are then empty
        if (!(node instanceof TerraformResource resource)
            || resource.getProvisioners() == null || resource.getProvisioners().isEmpty()) {
            return;
        }
        JsonNode provisioners = SYNTHESISED.valueToTree(resource.toTerraform())
                                           .path("resource")
                                           .path(resource.getTerraformResourceType())
                                           .path(resource.getFriendlyUniqueId())
                                           .path(PROVISIONER);
        boolean changed = false;
        for (JsonNode provisioner : provisioners) {
            if (provisioner.path(LOCAL_EXEC) instanceof ObjectNode localExec) {
                localExec.with(ENVIRONMENT).put(KUBECONFIG, kubeConfigPath);
                changed = true;
            }
        }
        if (changed) {
            // an override is merged into the synthesised block, appending to its list, so the list must go first
            resource.setProvisioners(List.of());
            resource.addOverride(PROVISIONER, PLAIN.convertValue(provisioners, List.class));
        }
    }

}
