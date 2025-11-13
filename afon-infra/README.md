# Types of deployment

There are two.

1. Just Polaris. This contains everything that is needed to vend credentials.
2. The whole stack (Jupyter and Spark in addition to Polaris)

# What the Java code does

The code does not provision the environment.
It creates the input for Terraform/Tofu.

When the Java is run, run `cd ./cdktf.out/stacks/YOUR_DEPLOYMENT`.
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

# Future improvements

In AWS, deleting the Polaris deployment is fine.
However, destroying the whole stack in AWS is problematic.
It appears that there are lingering AWS infrastructure components.
Run `health.emrys.afon.cdk.AwsConstruct` to print out helpful command lines.
In particular, this should blatt everything.

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

