package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;

/**
 * A claim on a volume that outlives the cluster, see {@link CloudPlatform#retainedVolumeClaim}.
 *
 * @param name  the name of the PersistentVolumeClaim, in the namespace it was asked for in
 * @param claim what a workload mounting it must depend on
 */
public record RetainedVolumeClaim(String name, ITerraformDependable claim) {
}
