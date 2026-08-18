# Operations

## Technical requirements 

To use Afon, you'll to satisfy the following technical requirements.

### Cloud provider

A cloud provider is required. The supported providers are: AWS, Azure and GCP.

We assume that the roles and permissions to create a kubernetes cluster have been set up.
For the chosen cloud provider, run the corresponding script:

```shell
./afon.sh -a exec -t script --exec-script <cloud>/create.sh
```
where valid cloud values are `{aws|azure|gcp}`.

### Polaris Database

An external database is required to store the Polaris metadata.

Credentials to access the database are specified in [configuration](configuration.md)

### OAuth2 Security Provider

Finally, if you want to use Jupyter/Guacamole, an OAuth2 security provider is required.

Afon uses Google Cloud as provider, so you'll need a Google account to register your domain in
[Google Auth](https://console.cloud.google.com/auth/clients?project=afon-core).

Doing so will generate the OAUTH2 client id and secret that can be set in your [configuration](configuration.md). 

The OAUTH2 cookie secret can be generated using command: 
```shell
openssl rand -hex 16
```

## Using the stack

We assume the stack is deployed (using the deployment [stacks](stacks.md) documentation).

### Logging in (multi-factor authentication)

Logging in to Jupyter or Guacamole goes through KeyCloak, which requires a second factor: a
time-based one-time code from an authenticator app (FreeOTP, Google Authenticator, Microsoft
Authenticator, ...).

The first time a user signs in, KeyCloak shows a "Mobile Authenticator Setup" page after their
username and password: they scan the barcode with their app and type the six digits it shows.
Every subsequent sign-in asks only for the six digits.

This is provisioned by `KeyCloakProvider.configureMfa`, which the user-onboarding step runs against
the `polaris` realm. It binds the realm to two copies of KeyCloak's built-in flows:

| Flow                  | Bound to              | Effect                                            |
|-----------------------|-----------------------|---------------------------------------------------|
| `browserFlow`         | `browser-mfa`         | a one-time code is always required                |
| `directGrantFlow`     | `direct-grant-no-otp` | no one-time code is asked for                     |

The second is what keeps Spark, Trino and the CLI working. They obtain tokens with a
username/password (a "direct grant"), and KeyCloak's stock direct-grant flow demands a one-time code
from any user who has enrolled one - which, with MFA on, is everybody. A batch job has nobody to read
an authenticator app, so the one-time code is required of interactive logins only; a direct grant is
protected by its confidential client's secret instead.

Set `KC_MFA_ENABLED=false` in your [configuration](configuration.md) to leave both flows alone. It
defaults to `true`.

If a user loses their authenticator app, delete their OTP credential in the KeyCloak admin console
(Users -> the user -> Credentials) and they will be walked through setup again at their next login.

### Viewing Jupyter via Guacamole

You can view Jupyter via [Guacamole](https://emrysjupyter.click/guacamole) 
(or using whichever Jupyter domain you're configured in the [configuration](configuration.md)).

Note that we use Apache Guacamole to stream pixels, and X11 VNC to render the images on the server side
(see `start-vnc.sh`).

That is, Guacamole runs in its own Kubernetes pod and streams the X11 data from the Jupyter pod.

## Diagnostics

### Polaris

You can examine the status of a table using the `curl` command aimed at your Polaris endpoint.
For example, for Emrys's AWS Polaris:
```shell
TOKEN=$(curl -X POST "https://emryspolaris.click/api/catalog/v1/oauth/tokens" -H "Content-Type: application/x-www-form-urlencoded" -d "grant_type=client_credentials&client_id=<polaris_id>&client_secret=<polaris_secret>&scope=PRINCIPAL_ROLE:ALL" | jq -r '.access_token')
curl -X GET "https://emryspolaris.click/api/catalog/v1/aws/namespaces/acceptancetests/tables/AwsAccessSpec251117511401" -H "Authorization: Bearer ${ACCESS_TOKEN}"  | jq
```
Note how this uses credentials and domain names you can find in the [configuration](configuration.md).

### Domain names

Some of the services take a few minutes to become live.
This is unavoidable.

For instance, domain names need to propagate.
You should see the Polaris ingress before Terraform has completed with something like:

```shell
kubectl get ingress
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

### Logs

Similarly, the Spark Connect container must download a lot of JARs before being ready to run.
See its status with:

```shell
kubectl logs spark-connector-0-driver
```

## AWS

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


### Usage

For some hints as to what to execute, try running:

```shell
./afon.sh -a exec --exec-class ops.GeneratePySparkContextMain
```
It will generate some PySpark to run in Jupyter and SQL to run in Snowflake.

### Registering a table with Polaris

When a new version of Polaris is started up, it has nothing in it.
So, create the warehouses with:

```shell
./afon.sh -a exec --exec-class ops.ConfigureClusterPolarisMain
```
and register tables with something like:

```shell
./afon.sh -a exec --exec-class ops.RegisterPolarisTableMain --exec-args "AWS acceptancetests AwsAccessSpec s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json emryspolaris.click <user_polaris_id> <user_polaris_secret>"
```
