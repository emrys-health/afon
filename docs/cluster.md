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

## Logging & Monitoring

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

