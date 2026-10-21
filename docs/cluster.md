# Kubernetes Cluster

We focus on *what* the cluster is and *why* we chose it.

# Deployment

We assume cluster is set up and the stack has been deployed to the cluster.
See the [stacks](stacks.md) on how to deploy the stack.

# Pods

To list the pods in the cluster:
```shell
kubectl get pods -A
```

A healthy cluster (on AWS) will look something like:
```shell
NAMESPACE     NAME                                                       READY   STATUS      RESTARTS        AGE
default       alertmanager-kube-prometheus-stack-alertmanager-0          2/2     Running     0               2d22h
default       emrys-master-0                                             1/1     Running     0               2d22h
default       emrys-worker-0                                             1/1     Running     0               2d22h
default       emrys-worker-1                                             1/1     Running     0               2d22h
default       guacamole-db-init-xv559                                    0/1     Completed   0               2d22h
default       guacamole-deployment-847bfbc549-vmktx                      2/2     Running     0               2d22h
default       hub-deployment-6777cd5cd6-wjtvl                            1/1     Running     0               2d22h
default       jupyterhub-proxy-deployment-8cfd699f6-f2wm9                1/1     Running     0               2d22h
default       keycloak-deployment-7c6f6665bb-gkxtn                       1/1     Running     0               2d14h
default       kube-prometheus-stack-grafana-6d75dbdd5c-vwzlq             3/3     Running     0               2d22h
default       kube-prometheus-stack-kube-state-metrics-65458c595-bw9z5   1/1     Running     0               2d22h
default       kube-prometheus-stack-operator-5d85bd8b4-bzdbw             1/1     Running     0               2d22h
default       kube-prometheus-stack-prometheus-node-exporter-27n94       1/1     Running     0               2d22h
default       kube-prometheus-stack-prometheus-node-exporter-6srn2       1/1     Running     0               2d22h
default       kube-prometheus-stack-prometheus-node-exporter-ljv8h       1/1     Running     0               2d22h
default       loki-0                                                     2/2     Running     0               2d22h
default       oauth2-proxy-deployment-7cc6555dcc-4nmzh                   1/1     Running     0               2d22h
default       polaris-deployment-7775b67b59-z9pgg                        1/1     Running     0               2d22h
default       prometheus-kube-prometheus-stack-prometheus-0              2/2     Running     0               2d22h
default       promtail-8fj5c                                             1/1     Running     0               2d22h
default       promtail-gjb7j                                             1/1     Running     0               2d22h
default       promtail-zwj9v                                             1/1     Running     0               2d22h
default       spark-connector-0-driver                                   1/1     Running     0               2d22h
default       spark-kubernetes-operator-6fc558c7bb-4c7mc                 1/1     Running     0               2d22h
default       trino-coordinator-86d75b5dd4-9h6jf                         1/1     Running     0               2d22h
default       trino-worker-75749bc7bb-7vf7l                              1/1     Running     0               2d22h
default       trino-worker-75749bc7bb-8rrw5                              1/1     Running     0               2d22h
kube-system   aws-load-balancer-controller-chart-5df9d9fd86-pdtjs        1/1     Running     0               2d22h
kube-system   aws-load-balancer-controller-chart-5df9d9fd86-zhz7p        1/1     Running     0               2d22h
kube-system   cilium-envoy-gbpsd                                         1/1     Running     0               2d22h
kube-system   cilium-envoy-k5dcc                                         1/1     Running     0               2d22h
kube-system   cilium-envoy-xl4d7                                         1/1     Running     0               2d22h
kube-system   cilium-hcqxr                                               1/1     Running     1 (2d22h ago)   2d22h
kube-system   cilium-operator-85b66c9cb4-5pvzs                           1/1     Running     0               2d22h
kube-system   cilium-operator-85b66c9cb4-7xnwc                           1/1     Running     0               2d22h
kube-system   cilium-w9tln                                               1/1     Running     1 (2d22h ago)   2d22h
kube-system   cilium-wdsx9                                               1/1     Running     0               2d22h
kube-system   coredns-85b99dc87-9tkls                                    1/1     Running     0               2d22h
kube-system   coredns-85b99dc87-mqjwf                                    1/1     Running     0               2d22h
kube-system   hubble-relay-587fd6785-t2d87                               1/1     Running     0               2d22h
kube-system   hubble-ui-f799d8b94-t8qpx                                  2/2     Running     0               2d22h
kube-system   metrics-server-8699ccc578-5xtjw                            1/1     Running     0               2d22h
```

In general, a pod with `operator` in its name is an "SRE in a pod".
They automate deployment and self-heal the cluster.

## The `default` namespace

### The Spark pods

The pods `emrys-*` are the Spark master/worker nodes.

The `spark-kubernetes-operator-*` pod manages the Spark cluster by (for example) spinning up worker and driver pods.

It also manages the `spark-connector-0-driver` pod.
This is the proxy between the pod running Jupyter and the Spark cluster.
The sensitive credentials used to talk to Polaris live here and are inaccessible to the average Jupyter user.

You can see those credentials with:
```shell
kubectl get sparkapplications spark-connector -o yaml | grep token
...
    spark.sql.catalog.aws.oauth2-server-uri: https://emrysoauth.click/realms/polaris/protocol/openid-connect/token
...
```

### The Jupyter pods 

The `hub-deployment-*` pod hosts the Jupyter Hub. 
We've slightly modified the Docker image (scripts/docker/jupyter/Dockerfile) so it uses Spark v4 libraries.

When the user logs into the Jupyter, using the `AWS_JUPYTER_DOMAIN_NAME`, a new *personal* pod is spun up with the 
username used to log in in the pod name.   

You can find the pod name by running
```shell
kubectl get pods | grep <username> | awk '{print $1}'
```

### The Polaris pods

The `polaris-deployment-*` pod hosts our Polaris instance. 

The interesting thing about this pod is that it mounts the credentials to talk to the clouds as a filesystem.
```shell
kubectl exec -it spark-kubernetes-operator-6fc558c7bb-4c7mc -- bash 
spark@spark-kubernetes-operator-6fc558c7bb-4c7mc:~/operator$ df -h
Filesystem      Size  Used Avail Use% Mounted on
overlay          50G  9.1G   41G  19% /
tmpfs            64M     0   64M   0% /dev
/dev/nvme0n1p1   50G  9.1G   41G  19% /etc/hosts
shm              64M     0   64M   0% /dev/shm
tmpfs           2.0G   12K  2.0G   1% /run/secrets/kubernetes.io/serviceaccount
tmpfs           7.7G     0  7.7G   0% /proc/acpi
tmpfs           7.7G     0  7.7G   0% /sys/firmware
```
Note the `/run/secrets/kubernetes.io/serviceaccount` mount point. 

## The `kube-system` namespace

Polaris reads its credentials from the Kubernetes secret `polaris-secret`, which `PolarisConfig` builds from the
configuration when it generates the Terraform plan, and mounts as a volume. The other workloads work the same way, eg
`kcsecrets` for Keycloak and `jupyterhub-secrets` for JupyterHub. No workload reads AWS Secrets Manager.

The `aws-load-balancer-controller-chart-*` pods are the bridge between the Kubernetes cluster and the greater internet.
When Emrys offers services to the outside world (for example, Jupyter and Polaris HTTP/s access) this is done through K8s Ingresses.
But how does K8s tell the outside world how to access it? 
Well, if the K8s cluster lives in AWS, it can use AWS load balancers and these pods co-ordinate that.

The pods beginning with `aws-node-*` assign IP addresses to the others. 
These IP addresses are valid outside the K8s cluster but only within the AWS VPC.

The `coredns-*` and `kube-proxy-*` pods are standard in K8s and manage the domain name and traffic routing **within** the cluster.

### Networking

The `cilium-*` pods manage Cilium, the network controller for traffic within the K8s cluster.
Cilium (`cilium-`UUID) only deals with L3/L4 traffic (IP/TCP etc).
Cilium Envoy (`cilium-envoy-*`) deals with higher level traffic (including HTTP).
It allows network policies to be set at this level.

It's thanks to Cilium that we can limit network traffic out of the cluster and stop exfiltration.
The network constraint code lives in `NetworkConfig.java`, deployed by the `NetworkPoliciesComponent`.

#### Egress IP (AWS)

All outbound traffic from the AWS cluster leaves from a single public IP, so that a third party can
put the cluster on an allow-list.  The nodes run in private subnets (`10.0.11.0/24`, `10.0.12.0/24`)
whose default route is a NAT gateway holding one Elastic IP; the public subnets (`10.0.1.0/24`,
`10.0.2.0/24`) are left to the internet-facing load balancers and the NAT gateway itself.

Because Cilium runs in ENI IPAM mode, pods get real VPC addresses on ENIs that the Cilium operator
creates, and it chooses their subnet.  `eni.subnetIDsFilter` therefore pins ENI allocation to the
private subnets - without it a pod can land in a public subnet, where it has neither a public IP nor
a NAT route and so no egress at all.

By default a new Elastic IP is allocated on apply and reported as the `egressIp` Terraform output:

```shell
tofu output egressIp
```

That IP is released again by `tofu destroy`, so the next deployment gets a different address.  To
keep one address across rebuilds, allocate an Elastic IP outside the stack:

```shell
aws ec2 allocate-address --domain vpc
```

and set it in `afon-aws.properties`:

```properties
AWS_EGRESS_IP=<the allocated IP>
```

The stack then looks that address up rather than managing it, so a destroy leaves it allocated (and
still on the allow-list) for the next apply.

#### Egress IP (Azure)

By default Azure allocates the public IP that the outbound traffic of the AKS cluster leaves from, and it is released with the cluster,
so a rebuild changes it.  To keep one address across rebuilds, so that a third party can allow-list the cluster once, create a Standard,
static public IP in the resource group of the stack, outside it, with `azure-egress-ip`, which is idempotent and
creates it in `AZURE_RESOURCE_GROUP` and `AZURE_REGION`:

```shell
afon-cli.sh azure-egress-ip create
```

(`afon-cli.sh azure-egress-ip show` prints it again, and `create <name>` or `show <name>` use a name other than the default
`afon-egress-ip`.) It runs `az network public-ip create --sku Standard --allocation-method Static` and warns if an existing one
is not Standard and static. The script never deletes it, as its address may be on somebody's allow-list.

Set the address it prints in `afon-azure.properties`:

```properties
AZURE_EGRESS_IP=<the address>
```

The stack then makes that public IP the outbound IP of the cluster's load balancer, and reports it as the `egressIp` Terraform output.
It does not manage it, so a destroy leaves it allocated (and still on the allow-list) for the next apply. Azure cannot look a public IP up
by its address, so the stack lists those of `AZURE_RESOURCE_GROUP` and picks the one with the address: the plan fails if there is none there.
The cluster's identity is granted Network Contributor on that public IP, which it needs to attach it.

Only a stack that pins its egress IP uses the property, ie not `azure-keycloak`, as one public IP can serve one cluster's outbound traffic,
so two stacks pinning it conflict, see [stacks side by side](stacks.md#stacks-side-by-side). Left empty, nothing changes.

Setting it on a cluster that already runs changes its outbound IP, in place: `tofu plan` shows the cluster updated, not replaced, and
the role assignment created. Check the plan before applying it, and expect the following after the apply:

- The tunnel between the Kubernetes API server and the nodes reconnects, so `kubectl exec` and `logs` can fail for a minute or two with
  `Expected HTTP 101 response but was '500 Internal Server Error'`. Anything run against the cluster straight after, eg the smoke tests,
  can fail once for that reason: wait until the `konnectivity-agent` pods in `kube-system` stop restarting.
- Connections that the workloads had open through the old address are dropped silently, so the pools of those holding long-lived ones to
  an external service, eg Keycloak, Polaris and Guacamole to their database, can hang until they time out (Keycloak then answers `500`,
  `Acquisition timeout while waiting for new connection`, and the Spark Connect test cannot get a token). Restart them:
  `kubectl rollout restart deploy/keycloak-deployment deploy/polaris-deployment deploy/guacamole-deployment -n default`.
- Whoever allow-lists the cluster's address, eg a firewall in front of a database, must switch to the new one.

#### Egress IP (GCP)

By default Google allocates the addresses that the outbound traffic of the GKE cluster leaves from, through its Cloud NAT, and they are
released with it, so a rebuild changes them.  To keep one address across rebuilds, so that a third party can allow-list the cluster once,
create a regional, external static IP in the project and region of the stack, outside it, with `gcp-egress-ip`, which is idempotent and
creates it in `GCP_PROJECT_ID` and `GCP_REGION`:

```shell
afon-cli.sh gcp-egress-ip create
```

(`afon-cli.sh gcp-egress-ip show` prints it again, and `create <name>` or `show <name>` use a name other than the default
`afon-egress-ip`.) It runs `gcloud compute addresses create` and warns if an existing one is not external. The script never deletes it, as
its address may be on somebody's allow-list.

Set the address it prints in `afon-gcp.properties`:

```properties
GCP_EGRESS_IP=<the address>
```

The stack then gives its Cloud NAT that static IP alone (`MANUAL_ONLY`, in place of `AUTO_ONLY`), and reports it as the `egressIp`
Terraform output. It does not manage it, so a destroy leaves it allocated (and still on the allow-list) for the next apply. The stack
finds it among the static IPs of `GCP_PROJECT_ID` and `GCP_REGION` by its address, so the plan fails if there is none there.

Only a stack that pins its egress IP uses the property, ie `gcp-polaris`, and one address serves one Cloud NAT, so two
stacks pinning it would conflict, see [stacks side by side](stacks.md#stacks-side-by-side). Left empty, nothing changes.

Setting it on a cluster that already runs changes the addresses its outbound traffic leaves from, which breaks the connections that are
open for a moment: check the plan before applying it, and restart what holds long-lived connections to an external service afterwards,
as on [Azure](#egress-ip-azure). Whoever allow-lists the cluster's address must switch to the new one.

### Restricting who can reach Polaris (AWS)

Set `AWS_POLARIS_ALLOWED_CIDRS` to a comma-separated list of CIDRs to only admit those callers to
Polaris' ALB (via the `alb.ingress.kubernetes.io/inbound-cidrs` annotation).  The egress IP above is
added automatically, as pods calling Polaris by its public domain name come back in through the ALB
from that address.

Note that if this property is not defined, there are no restraints. 
You can, however, add them manually with something like:
```shell
kubectl annotate ingress polaris-ingress -n default --overwrite   alb.ingress.kubernetes.io/inbound-cidrs="35.177.204.48/32"
```
This annotation triggers the ALB to add the constraint as it watches any change here. 

This cannot be done with a Kubernetes `NetworkPolicy` `ipBlock`: the ALB terminates the client's
connection and opens its own, so the Polaris pod only ever sees the ALB's or a node's VPC address.

### Restricting who can reach Polaris (Azure)

Set `AZURE_POLARIS_ALLOWED_CIDRS` in `afon-azure.properties` to a comma-separated list of CIDRs to only admit those callers to
Polaris' nginx ingress (via its `nginx.ingress.kubernetes.io/whitelist-source-range` annotation).  The cluster's pod CIDR
(`10.244.0.0/16`, that of the AKS overlay network) is added automatically, as pods calling Polaris by its public domain name
reach the ingress from their own addresses, and so is the pinned `AZURE_EGRESS_IP`, if set, like the egress IP on AWS.  Left empty, there are
no restraints: the egress IP alone does not restrict Polaris.

The ingress controller can only tell the callers apart if the load balancer does not hide them: with the property set, the
controller's service gets `externalTrafficPolicy: Local`, else the callers would all arrive from a node's address.

The machine running `afon-cli.sh manage-stack setup` must be able to call Polaris, as it configures the Polaris users and catalogs through
its public endpoint, so it must be among the CIDRs, or the ingress unrestricted, while the Polaris steps run. The `network` smoke test needs
the opposite: that same machine must be stopped. So a stack deployed with the property set cannot be set up from a machine outside of it:
remove the restriction of the deployed cluster for the setup, then restore it. `kube-polaris-whitelist` shows, sets or removes the
restriction of the cluster that kubectl points at, on `aws` or `azure`, until the next deploy or `tofu-apply`, which sets it as the
config says:

```shell
afon-cli.sh azure-update-kube-config tre
afon-cli.sh kube-polaris-whitelist azure show
afon-cli.sh kube-polaris-whitelist azure set --caller                # for setup to call Polaris from this machine, and only from it
afon-cli.sh manage-stack setup azure-full configure_oidc_users_for_cloud_azure   # resumes setup from the step that failed
afon-cli.sh kube-polaris-whitelist azure set                         # the configured CIDRs plus the cluster's own addresses: this machine is off again
```

`set --caller` adds the IPv4 address of this machine to the list that `set` would apply, ie to the CIDRs given, else to the configured ones, plus
the cluster's own addresses. It asks `https://ifconfig.me` for the address (`CALLER_IP_URL` says another service), and fails, changing
nothing, if it cannot learn one. It is the address of this machine's route to the internet: through a VPN or a proxy that reaches the cluster
differently, Polaris sees another address, and the Polaris steps are still refused. `set` again, without `--caller`, takes the address off, as it
builds the list from the config and not from the annotation as it is. `remove` is the alternative that opens Polaris to everybody, which
leaves it open if the setup stops part way.

`set` also sets `externalTrafficPolicy: Local` on Azure, which the restriction needs. It is the CIDRs that a deploy would apply, and the
output says where each comes from. It refuses a cluster of the wrong kind for the cloud, eg the AKS one for `aws`.

You can also restrict a deployed cluster by hand, which a redeploy undoes:

```shell
kubectl annotate ingress polaris-ingress -n default --overwrite nginx.ingress.kubernetes.io/whitelist-source-range="203.0.113.7/32,10.244.0.0/16"
```

### Restricting who can reach Polaris (GCP)

Set `GCP_POLARIS_ALLOWED_CIDRS` in `afon-gcp.properties` to a comma-separated list of CIDRs to only admit those callers to
Polaris' nginx ingress (via its `nginx.ingress.kubernetes.io/whitelist-source-range` annotation), as on [Azure](#restricting-who-can-reach-polaris-azure).
The cluster's own addresses are added automatically, as pods calling Polaris by its public domain name reach the ingress from them: the
pod range (`10.20.0.0/16`), the node subnet (`10.10.0.0/24`), and the pinned `GCP_EGRESS_IP`, if set. Left empty, there are no restraints.

With the property set, the controller's service gets `externalTrafficPolicy: Local`, else the load balancer would hide the callers behind
a node's address. `kube-polaris-whitelist` shows, sets or removes the restriction of the cluster that kubectl points at, as it does for the
other clouds, and the same caveats about running `setup` and the `network` smoke test from a machine apply:

```shell
afon-cli.sh gcp-update-kube-config tre
afon-cli.sh kube-polaris-whitelist gcp show
afon-cli.sh kube-polaris-whitelist gcp remove
afon-cli.sh kube-polaris-whitelist gcp set
```

## Volumes that outlive the cluster (AWS)

Gitea's repositories are kept on an EBS volume that is not destroyed with the stack, so that a cluster of the same
name mounts the same volume again.  It is tagged `Name=gitea-<cluster>`, eg `gitea-full-emrys-cluster`, is in the
zone `<AWS_REGION>a`, and is sized by `GITEA_STORAGE_SIZE` (default `10Gi`).  On deploy, the stack imports the
volume of that name if there is one, else creates it.  Gitea's database `gitea_<cluster>` and its path
`gitea/<cluster>/` in `AWS_GITEA_BUCKET` are named after the same cluster, so all three are found again together.

`afon-cli.sh manage-stack delete` keeps the volume: it excludes every resource with `prevent_destroy` from
`tofu destroy`.  A plain `tofu destroy` fails on it instead, rather than delete it.  To destroy the stack by hand:

```shell
tofu destroy -exclude=aws_ebs_volume.retained_gitea_volume
```

While the cluster runs, Data Lifecycle Manager snapshots the volume daily at 03:00 UTC and keeps the last 7.
The snapshots outlive the cluster too, so those of earlier clusters stay until deleted.

To grow the volume, raise `GITEA_STORAGE_SIZE` and redeploy, which grows the EBS volume in place, then grow its
filesystem on the node running Gitea, as the volume is bound statically, outside the CSI driver's resizing:

```shell
kubectl debug node/<node> -it --image=ubuntu -- chroot /host resize2fs /dev/<device>
```

It cannot shrink.  To delete it for good, delete the stack, then `aws ec2 delete-volume --volume-id <id>`.

## Logging & Monitoring

### Polaris logs in S3 (AWS)

Set `AWS_POLARIS_LOG_BUCKET` to an S3 bucket, which `afon-cli.sh aws-create` creates if absent, to keep Polaris' logs after the pod, or the whole cluster,
is gone.  A Fluent Bit DaemonSet (`fluent-bit-polaris-*` in `kube-system`) tails the console output of the Polaris
containers and uploads it, at least every minute, as gzipped JSON lines (one per log line, with the pod's
metadata) to:

```
s3://<AWS_POLARIS_LOG_BUCKET>/polaris/<cluster>/<yyyy>/<mm>/<dd>/<HHMMSS>-<uuid>.json.gz
```

Fluent Bit assumes the IAM role `cdktf-eks-fluent-bit-role-<cluster>` via IRSA, which may only write under that
prefix.  On destroy, Polaris is removed before Fluent Bit, which uploads what it still holds when it stops.  To
read the logs:

```shell
aws s3 ls --recursive s3://<bucket>/polaris/<cluster>/
aws s3 cp s3://<bucket>/polaris/<cluster>/<key> - | gunzip | jq -r .log
```

Nothing is shipped when the property is empty.

### Monitoring

Currently, we use an external Grafana and tunneling into the cluster.
But Grafana (`kube-prometheus-stack-grafana-*`) is deployed as part of the Prometheus Helm chart.
So is the Alert Manager (`alertmanager-kube-prometheus-stack-alertmanager-0`).
It too is currently unused.

The Hubble UI (`hubble-ui-*`) shows connections that are in Cilium's (finite) memory.
Therefore, this GUI represents connection in a limited window in time not all connections ever made by the pods.
The window of time depends on traffic volumes, available memory etc.

Hubble relay (`hubble-relay-*`) collects the data from all the nodes and serves it.

## Security

Raw tokens are passed between Polaris and clients calling it. Therefore, communication must be encrypted.
In an HTTPS handshake, the client must call a hard-coded list of Certificate Authorities to check the domain
belongs to who is says it does. For this, we need a registered domain, not just an IP address of a domain name Kubernetes has assigned.

To make AWS aware of it, we need to annotate the Kubernetes Ingress with details that the ALB (AWS Load Balancer) pods pick up and use to manipulate the AWS infra outside of our Kubernetes.

Go to the [Route 53](https://us-east-1.console.aws.amazon.com/route53/v2/hostedzones?region=eu-west-2#) page in the Amazon console to claim and manage the actual domain.

