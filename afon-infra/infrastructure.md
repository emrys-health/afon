This file documents *what* the infrastructure is and *why* we chose it.
See the [REAMDME](./README.md) file for *how* to create the infrastructure.

A healthy K8s cluster **on AWS** should look something like this:
```shell
$ kubectl get pods -A
NAMESPACE         NAME                                                  READY   STATUS    RESTARTS   AGE
calico-system     calico-apiserver-78cd78c756-b5tcb                     1/1     Running   0          22h
calico-system     calico-apiserver-78cd78c756-fbzmj                     1/1     Running   0          22h
calico-system     calico-kube-controllers-644d797b6b-bdn9m              1/1     Running   0          22h
calico-system     calico-node-5vsjd                                     1/1     Running   0          22h
calico-system     calico-node-7wmzj                                     1/1     Running   0          22h
calico-system     calico-node-8hl2h                                     1/1     Running   0          22h
calico-system     calico-typha-6cd6677d6d-5k9bx                         1/1     Running   0          22h
calico-system     calico-typha-6cd6677d6d-mvsxt                         1/1     Running   0          22h
calico-system     goldmane-859fd45798-dkxxb                             1/1     Running   0          22h
calico-system     whisker-5d544968fb-24g9x                              2/2     Running   0          22h
default           emrys-master-0                                        1/1     Running   0          22h
default           emrys-worker-0                                        1/1     Running   0          22h
default           emrys-worker-1                                        1/1     Running   0          22h
default           jupyter-deployment-7985d67bcf-55tvv                   1/1     Running   0          22h
default           polaris-deployment-6999d5b5b4-j2t66                   1/1     Running   0          22h
default           spark-connector-0-driver                              1/1     Running   0          6h2m
default           spark-kubernetes-operator-7cdb84d876-psktf            1/1     Running   0          22h
kube-system       aws-load-balancer-controller-chart-6c67847cf7-gmftt   1/1     Running   0          22h
kube-system       aws-load-balancer-controller-chart-6c67847cf7-zhk9t   1/1     Running   0          22h
kube-system       aws-node-4cr6m                                        2/2     Running   0          22h
kube-system       aws-node-6mrch                                        2/2     Running   0          22h
kube-system       aws-node-x62zr                                        2/2     Running   0          22h
kube-system       coredns-6574fbb8f6-4tgwl                              1/1     Running   0          22h
kube-system       coredns-6574fbb8f6-bspng                              1/1     Running   0          22h
kube-system       csi-secrets-store-secrets-store-csi-driver-fq5sd      3/3     Running   0          22h
kube-system       csi-secrets-store-secrets-store-csi-driver-rmncl      3/3     Running   0          22h
kube-system       csi-secrets-store-secrets-store-csi-driver-whhsh      3/3     Running   0          22h
kube-system       kube-proxy-cdm9v                                      1/1     Running   0          22h
kube-system       kube-proxy-jxgng                                      1/1     Running   0          22h
kube-system       kube-proxy-l5mgf                                      1/1     Running   0          22h
tigera-operator   tigera-operator-656c6bc5bd-tdpj8                      1/1     Running   0          22h
```

## The `default` namespace

The pods `emrys-*` are just Spark master/worker nodes.

The `spark-kubernetes-operator-*` pod manages the Spark cluster by (for example) spinning up worker and driver pods.

It also manages the `spark-connector-0-driver` pod. 
This is the proxy between the pod running Jupyter and the Spark cluster.
The sensitive credentials used to talk to Polaris live here and are inaccessible to the average Jupyter user.
You can see those credentials with:
```shell
kubectl get sparkapplications spark-connector -o yaml
...
    spark.sql.catalog.gcp.token: eyJhbGci...
...
```

The `jupyter-deployment-*` pod hosts the Jupyter notebook. 
We've slightly modified the Docker image [see](../scripts/docker/jupyter/Dockerfile) so it uses Spark v4 libraries.

The `polaris-deployment-*` pod hosts our Polaris instance. 
The interesting thing about this pod is that it mounts the credentials to talk to the clouds as a filesystem.
```shell
$ kubectl exec -it spark-kubernetes-operator-7cdb84d876-psktf -- bash
spark@spark-kubernetes-operator-7cdb84d876-psktf:~/operator$ df -h
Filesystem      Size  Used Avail Use% Mounted on
overlay          20G   13G  7.6G  63% /
tmpfs           2.0G   12K  2.0G   1% /run/secrets/kubernetes.io/serviceaccount
...
```
Note tha `/run/secrets/kubernetes.io/serviceaccount` mount point. How this is managed is below.

## The `kube-system` namespace

The Polaris secrets are stored in AWS and can be seen with:
```shell
aws secretsmanager list-secrets
```
They're the ones called `cdktf-app-config-secret`, a name that comes from the code that creates the [Terrform plan](./src/main/java/health/emrys/afon/cdk/AwsConstruct.java).
The bridge between AWS and Kubernetes are the `csi-secrets-store-secrets-store-csi-driver-*` pods.

The `aws-load-balancer-controller-chart-*` pods are the bridge between the Kubernetes cluster and the greater internet.
When Emrys offers services to the outside world (for example, Jupyter and Polaris HTTP/s access) this is done through K8s Ingresses.
But how does K8s tell the outside world how to access it? 
Well, if the K8s cluster lives in AWS, it can use AWS load balancers and these pods co-ordinate that.


