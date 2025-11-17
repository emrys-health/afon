# Types of deployment

There are two.

1. Just Polaris. This contains everything that is needed to vend credentials. We have deployments for all three cloud providers.
2. The whole stack (Jupyter and Spark in addition to Polaris). At the moment, this is just available for AWS. More coming soon.

# What the Java code does

The code does not provision the environment.
It creates the input for Terraform/Tofu.

After the Java is run, run `cd ./cdktf.out/stacks/YOUR_DEPLOYMENT`.
There, you can run `tofu apply` (or `tofu destroy` if you want to tear the stack down).

### Deployment #1

Run the classes for creating markup to create just Polaris by running (in the `afon-infra` directory):

`mvn -Dexec.mainClass=health.emrys.afon.cdk.AwsKubernetesPolaris exec:java`

`mvn -Dexec.mainClass=health.emrys.afon.cdk.AzureKubernetesPolaris exec:java`

`mvn -Dexec.mainClass=health.emrys.afon.cdk.GcpKubernetesPolaris exec:java`

for each cloud provider.

Note that the credentials in your `env.properties` are used by the deployed Polaris.

### Deployment #2

If you want to create config for the whole stack in AWS, run:

`mvn -Dexec.mainClass=health.emrys.afon.cdk.AwsWholeStack exec:java`

This will output your `cdk.tf.json` file.
Run it with `tofu apply -auto-approve` if you are happy with it.
(If it's being run for the first time, you must run `tofu init` first).
After about 10 minutes, you should have a working cluster.
Check this by running `kubectl get pods`. 
You should see Polaris and Jupyter.

"But where is Spark?" I hear you gasp. 
Relax, dear reader. 
For this, we need to run:

`mvn -Dexec.mainClass=health.emrys.afon.cdk.SparkClusterDeployment exec:java`

In the same directory, you'll see a new `cdk.tf.json`. 
So, run `tofu apply -auto-approve`.

*Why is this a two step process?*
Spark needs to Polaris to be up and running before we can configure it since Polaris generates keys at runtime.

# Future improvements

### Tearing down the AWS environment

In all cloud providers, deleting the Polaris deployment is fine.
However, destroying the whole stack in AWS is problematic.
It appears that there are lingering AWS infrastructure components.
Run `health.emrys.afon.cdk.AwsConstruct` to print out helpful command lines.
In particular, this should blatt everything:

```shell
export VPC=$(aws ec2 describe-vpcs   --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc']].VpcId"   --output text)

eksctl delete cluster --name spark-cluster --region eu-west-2

for SN in $(aws ec2 describe-network-interfaces --filters Name=vpc-id,Values=$VPC --region eu-west-2 --query 'NetworkInterfaces[*].[SubnetId]' --output text) ; do {
    echo $SN ;
    aws ec2 delete-subnet --subnet-id $SN --region $REGION ;
} done

aws ec2 delete-vpc --vpc-id $VPC

aws iam delete-role --role-name  cdktf-eks-cluster-role
```

### Addressing the 2-step Spark deployment

We can probably configure Polaris in a more [production like](https://polaris.apache.org/releases/1.0.0/configuring-polaris-for-production/) manner.
If its metastore credentials were [persistent](https://github.com/emrys-health/afon-core/issues/6) we might not need the 2 phased delpoyment. 

### Be GitOps aligned

The idea in GitOps is that we check Terraform code into Git.
However, our Terraform can contain confidential details. 
If we are to adopt GitOps, we need to pull this sensitive data out.