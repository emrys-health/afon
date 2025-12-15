# Types of deployment

There are two.

1. Just Polaris. This contains everything that is needed to vend credentials. We have deployments for all three cloud providers.
2. The whole stack (Jupyter and Spark in addition to Polaris). At the moment, this is just available for AWS. More coming soon.

### Deployment #1

Run the classes for creating HCL to create just Polaris by running (in the `afon-infra` directory):

`mvn -Dexec.mainClass=health.emrys.afon.cdk.AwsKubernetesPolaris exec:java`

`mvn -Dexec.mainClass=health.emrys.afon.cdk.AzureKubernetesPolaris exec:java`

`mvn -Dexec.mainClass=health.emrys.afon.cdk.GcpKubernetesPolaris exec:java`

for each cloud provider. Then run: 
```shell
cd cdktf.out/stacks/jupyter-deployment
tofu init
tofu apply -auto-approve -target=aws_eks_cluster.spark-cluster
tofu apply -auto-approve
````

Note that the credentials in your `env.properties` are used by the deployed Polaris.

You can examine the status of a table using the `curl` command aimed at your Polaris endpoint.
For example:
```shell
curl -X GET "https://emryspolaris.click/api/catalog/v1/aws/namespaces/acceptancetests/tables/AwsAccessSpec251117511401" -H "Authorization: Bearer ${ACCESS_TOKEN}"  | jq
```

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
So, run `tofu apply -auto-approve` again.

*Why is this a two step process?*
Spark needs to Polaris to be up and running before we can configure it since Polaris generates keys at runtime.

### Important

Some of the services take a few minutes to become live.
This is unavoidable.

For instance, domain names need to propagate.
You should see the Polaris ingress before Terraform has completed with something like:

```shell
$ kubectl get ingress
```
You should see something like:
```shell
NAME              CLASS   HOSTS                ADDRESS                                                                  PORTS   AGE
polaris-ingress   alb     emryspolaris.click   k8s-default-polarisi-2f4263fc44-1173884799.eu-west-2.elb.amazonaws.com   80      14m
```

However, its domain name my take a few minutes to propagate around the internet.
Check that with:

```shell
dig A emryspolaris.click +short
```

Similarly, the Spark Connect container must download a lot of JARs before being ready to run.
See its status with:

```shell
kubectl logs spark-connector-0-driver
```

(This last one could potentially be mitigated...)

### What now?

You probably want to see Jupyter or need the Polaris access token if you use Snowflake.
So, run:

```shell
mvn -Dexec.mainClass=health.emrys.afon.infra.InfraExplorer exec:java
```

and for some suggests as to what to execute, running this:

```shell
mvn -Dexec.mainClass=health.emrys.afon.core.configuration.SparkContextConfigurationPrinter exec:java
```

will generate some PySpark to run in Jupyter and SQL to run in Snowflake.

### Registering a table with Polaris

When a new version of Polaris is started up, it has nothing in it.
So, create the warehouses with:

```shell
mvn -Dexec.mainClass=health.emrys.afon.infra.ClusterPolarisConfiguration exec:java
```

and register tables with something like:

```shell
mvn exec:java -Dexec.mainClass=health.emrys.afon.core.configuration.PolarisRegisterMain  -Dexec.args="AWS acceptancetests AwsAccessSpec251117511401 s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json" 
```

or similar.


# What the Java code does

The code does not provision the environment.
It creates the input for Terraform/Tofu.

After the Java is run, run `cd ./cdktf.out/stacks/YOUR_DEPLOYMENT`.
There, you can run `tofu apply` (or `tofu destroy` if you want to tear the stack down).

# Future improvements

### Tearing down the AWS environment

In all cloud providers, deleting the Polaris deployment is fine.
However, destroying the whole stack in AWS is problematic.
It appears that there are lingering AWS infrastructure components.
Run `health.emrys.afon.cdk.AwsConstruct` to print out helpful command lines.
In particular, the output of this script should blatt everything:

```shell
mvn -Dexec.mainClass=health.emrys.afon.cdk.AwsConstruct exec:java
```
*Note:* Run the output twice!

### Addressing the 2-step Spark deployment

We can probably configure Polaris in a more [production like](https://polaris.apache.org/releases/1.0.0/configuring-polaris-for-production/) manner.
If its metastore credentials were [persistent](https://github.com/emrys-health/afon-core/issues/6) we might not need the 2 phased delpoyment. 

### Be GitOps aligned

The idea in GitOps is that we check Terraform code into Git.
However, our Terraform can contain confidential details. 
If we are to adopt GitOps, we need to pull this sensitive data out.