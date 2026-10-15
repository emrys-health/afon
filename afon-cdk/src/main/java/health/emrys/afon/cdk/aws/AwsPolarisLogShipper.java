package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import health.emrys.afon.cdk.PolarisLogShipper;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;

/**
 * Ships Polaris' console logs to S3, under {@code s3://<bucket>/polaris/<cluster>/<yyyy>/<mm>/<dd>/}.  Fluent Bit
 * assumes its role via IRSA, which may only write under that prefix.
 */
public class AwsPolarisLogShipper extends PolarisLogShipper {

    private final AwsCloudProvider cloudProvider;
    private final String           bucket;
    private final String           region;
    /** The ARN of the IRSA role, once {@link #prerequisites} has created it. */
    String roleArn;

    public AwsPolarisLogShipper(AwsCloudProvider cloudProvider, String bucket, String region) {
        this(cloudProvider, cloudProvider.getComponentNames().getCluster(), bucket, region);
    }

    AwsPolarisLogShipper(AwsCloudProvider cloudProvider, String cluster, String bucket, String region) {
        super(cluster);
        this.cloudProvider = cloudProvider;
        this.bucket        = bucket;
        this.region        = region;
    }

    @Override
    protected List<ITerraformDependable> prerequisites(Construct scope,
                                                       List<@NonNull ITerraformDependable> clusterDependOn) {
        IamRole role = cloudProvider.createIrsaRole(
            "polarisLogShipperRole",
            cloudProvider.getComponentNames().getPolarisLogShipperRoleName(),
            NAMESPACE,
            SERVICE_ACCOUNT,
            putObjectPolicy());
        roleArn = role.getArn();
        List<ITerraformDependable> dependsOn = new ArrayList<>();
        dependsOn.add(role);
        // The chart always creates a (metrics) Service, which the load balancer controller's webhook must admit.
        dependsOn.addAll(cloudProvider.getServiceDependables());
        return dependsOn;
    }

    String putObjectPolicy() {
        return format("""
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Action": "s3:PutObject",
                  "Resource": "arn:aws:s3:::%s/%s/*"
                }
              ]
            }""", bucket, prefix);
    }

    @Override
    protected String serviceAccountValues() {
        return format("""
            annotations:
              eks.amazonaws.com/role-arn: %s
            """, roleArn);
    }

    @Override
    protected String output() {
        return format("""
            [OUTPUT]
                Name s3
                Match polaris.*
                bucket %s
                region %s
                use_put_object On
                compression gzip
                total_file_size 50M
                upload_timeout 30m
                store_dir /var/log/fluent-bit-polaris/s3
                s3_key_format /%s/%%Y/%%m/%%d/%%H%%M%%S-$UUID.json.gz
            """, bucket, region, prefix);
    }
}
