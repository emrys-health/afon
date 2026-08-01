# Assumptions

### Cloud permissions

We assume that if you running Afon, you have an AWS, Azure and/or GCP cloud.
We also assume that the roles and permissions to create a K8s cluster have been set up.
These include running:
```shell
../scripts/aws/create.sh    # for AWS
../scripts/azure/create.sh  # for Azure
../scripts/gcp/create.sh    # for Google
```

### External Database

We also assume that you have a Database to store the Polaris metadata.
Credentials to access that live in [env.properties](../config/env.properties)

### OAuth Provider

Finally, if you want to use Jupyter/Guacamole, you need to get past the Oauth2 security that we delegate to Google.
So, you'll need a Google account login.

If you want to set up OAuth via Google, then register your domain [here](https://console.cloud.google.com/auth/clients?project=afon-core).
Doing so will generate the client ID and secret you then put in `env.properties`.
(The cookie secret you generate yourself using something like `openssl rand -hex 16`).

# Deployment

Run everything from the top-level dir.

NOTE: Only AWS has the full stack. 
Azure and GCP just host Polaris at the moment. There's no particular reason for this other than expediency.

NOTE: If you really foul things up, you'll leave the environment in an unstable state.
Consult the `*Nuke.java` files to destroy your environment totally.
They will output shell scripts that you can run.
You may need to run them multiple times for the VPC to disappear from your cloud.


### Automated deployment

To actually run all the steps in each deployment above, run:

```shell
./afon.sh -a exec --exec-class ops.DeployAwsFullStackMain
./afon.sh -a exec --exec-class ops.DeployAzurePolarisMain
./afon.sh -a exec --exec-class ops.DeployGcpPolarisMain
./afon.sh -a exec --exec-class ops.DeployExternalCatalogsMain
```

Note that the credentials in your `config/env*.properties` are used by the deployed Polaris instance.

### Deploying the environment

To deploy the environment, run:

```shell
./afon.sh -a exec --exec-class ops.DeployExternalCatalogsMain
```

This will create an AWS and Azure K8s cluster both running a Polaris, populate it and run against them a simple Spark test locally.

# Viewing the SRE

You can view Jupyter at [`https://emrysjupyter.click/guacamole`](https://emrysjupyter.click/guacamole) 
(or whichever domain you have defined in the `config/afon*.properties` file).

Note that we use Apache Guacamole to stream pixels, and X11 VNC to render the images on the server side
(see `start-vnc.sh`).

That is, Guacamole runs in its own Kubernetes pod and streams the X11 data from the pod running Jupyter.

# Diagnostics

#### Polaris

You can examine the status of a table using the `curl` command aimed at your Polaris endpoint.
For example, for Emrys's AWS Polaris:
```shell
TOKEN=$(curl -X POST "https://emryspolaris.click/api/catalog/v1/oauth/tokens" -H "Content-Type: application/x-www-form-urlencoded" -d "grant_type=client_credentials&client_id=root&client_secret=s3cr3t&scope=PRINCIPAL_ROLE:ALL" | jq -r '.access_token')
curl -X GET "https://emryspolaris.click/api/catalog/v1/aws/namespaces/acceptancetests/tables/AwsAccessSpec251117511401" -H "Authorization: Bearer ${ACCESS_TOKEN}"  | jq
```
Note how this uses credentials and domain names you can find in the `config/*.properties` files.

#### Domain names

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

#### Logs

Similarly, the Spark Connect container must download a lot of JARs before being ready to run.
See its status with:

```shell
kubectl logs spark-connector-0-driver
```

(This last one could potentially be mitigated...)


# Domain names

For our PoC, the approach to managing different domain names is different per cloud provider (see below).
But in general, get the DNSs for the cloud to which you want to deploy and assign them to the name servers
of the provider that sold the domain names.
Once this is done, a cloud managed Kubernetes will talk to the cloud provider and have it point the domain name
at a container it runs.
This all happens as part of a `tofu apply`.

You can find the domain name servers for your cloud provider in its web GUI if you just search for `DNS`.

### AWS

Pointing the domain names at an IP address in Kubernetes is easy in AWS as the whole thing is integrated.
It's a bit harder in Azure and GCP.

The following domain names are provided (from AWS Route 53):
```
emryspolaris.click
emryspolarisgcp.click
emryspolarisazure.click
```

In each case, you have to update Route53's nameservers to the respective cloud's nameservers.
Then, the Kubernetes cluster for that cloud can point the domain name at its own ingress.

### GCP

GCP assumes that the managed zone exists:
```shell
$ gcloud dns managed-zones describe polaris-zone --project=afon-core
cloudLoggingConfig:
  kind: dns#managedZoneCloudLoggingConfig
creationTime: '2026-03-06T13:59:46.276Z'
description: Managed by Terraform
dnsName: emryspolarisgcp.click.
id: '3399931746996979034'
kind: dns#managedZone
labels:
  goog-terraform-provisioned: 'true'
name: polaris-zone
nameServers:
- ns-cloud-c1.googledomains.com.
- ns-cloud-c2.googledomains.com.
- ns-cloud-c3.googledomains.com.
- ns-cloud-c4.googledomains.com.
visibility: public
```

### Azure

To delegate responsibility of where to point `emryspolarisazure.click`, run:

```shell
az network dns zone create  --resource-group afon_resource_group  --name emryspolarisazure.click
```
(Note AWS expects the trailing period you usuaully find in DNS entries to be removed)


# Usage

For some suggests as to what to execute, running this:

```shell
mvn exec:exec -Pexec -Dexec.mainClass=health.emrys.afon.ops.GeneratePySparkContextMain
```

will generate some PySpark to run in Jupyter and SQL to run in Snowflake.

### Registering a table with Polaris

When a new version of Polaris is started up, it has nothing in it.
So, create the warehouses with:

```shell
./afon.sh -a exec --exec-class ops.ConfigureClusterPolarisMain
```

and register tables with something like:

```shell
./afon.sh -a exec --exec-class ops.RegisterPolarisMain --exec-args="AWS acceptancetests AwsAccessSpec251117511401 s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json" 
```

# What the Java code does

The Java code creates the config to create the cloud environment and delegates it to tofu to execute it.

After the Java is run, the tofu plan is in `afon-ops/cdktf.out/stacks/{aws,azure,gcp}`.
Thereafter, you can run tofu apply or destroy commands via 
```shell
./afon.sh -a tofu-{apply,destroy}
```

# Future improvements

### Tearing down the AWS environment

In all cloud providers, deleting the Polaris deployment is fine.
However, destroying the whole stack in AWS is problematic.
It appears that there are lingering AWS infrastructure components.
Run `health.emrys.afon.cdk.aws.AwsNuke` to print out helpful command lines.
In particular, the output of this script should blatt everything:

```shell
./afon.sh -a exec --exec-class cdk.aws.AwsNuke
```

This is a shell script. 
The advantage of this is that you can use it if your accidentally delete your Terraform state.

And if all else fails. go to the [AWS VPCs](https://eu-west-2.console.aws.amazon.com/vpcconsole/home?region=eu-west-2#vpcs:)
and delete everything you created by hand.

### Be GitOps aligned

The idea in GitOps is that we check Terraform code into Git.
However, our Terraform can contain confidential details. 
If we are to adopt GitOps, we need to pull this sensitive data out.
