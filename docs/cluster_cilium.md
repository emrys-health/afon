A healthy looking cluster looks something like:
```shell
$ kubectl get pods -A
NAMESPACE     NAME                                                       READY   STATUS    RESTARTS      AGE
default       alertmanager-kube-prometheus-stack-alertmanager-0          2/2     Running   0             17h
default       emrys-master-0                                             1/1     Running   0             20h
default       emrys-worker-0                                             1/1     Running   0             20h
default       emrys-worker-1                                             1/1     Running   0             20h
default       guacamole-deployment-7bb97557db-fnnxc                      2/2     Running   0             20h
default       jupyter-deployment-f8677b85-v8qxh                          1/1     Running   0             20h
default       kube-prometheus-stack-grafana-6d75dbdd5c-l4nvp             3/3     Running   0             17h
default       kube-prometheus-stack-kube-state-metrics-65458c595-kzpbc   1/1     Running   0             17h
default       kube-prometheus-stack-operator-5d85bd8b4-kpsqq             1/1     Running   0             17h
default       kube-prometheus-stack-prometheus-node-exporter-2j5td       1/1     Running   0             17h
default       kube-prometheus-stack-prometheus-node-exporter-kg4cr       1/1     Running   0             17h
default       kube-prometheus-stack-prometheus-node-exporter-xgq84       1/1     Running   0             17h
default       loki-0                                                     2/2     Running   0             17h
default       oauth2-proxy-deployment-648b87f64d-f92bb                   1/1     Running   0             20h
default       polaris-deployment-7775b67b59-trksd                        1/1     Running   1 (20h ago)   20h
default       prometheus-kube-prometheus-stack-prometheus-0              2/2     Running   0             17h
default       promtail-gzk6g                                             1/1     Running   0             17h
default       promtail-m8khr                                             1/1     Running   0             17h
default       promtail-swwm7                                             1/1     Running   0             17h
default       spark-connector-0-driver                                   1/1     Running   0             18h
default       spark-kubernetes-operator-6fc558c7bb-pkvqs                 1/1     Running   0             20h
kube-system   aws-load-balancer-controller-chart-b8c98d686-dwvcn         1/1     Running   0             20h
kube-system   aws-load-balancer-controller-chart-b8c98d686-mw7b7         1/1     Running   0             20h
kube-system   cilium-bchl8                                               1/1     Running   0             20h
kube-system   cilium-envoy-cfv2c                                         1/1     Running   0             20h
kube-system   cilium-envoy-ljn4v                                         1/1     Running   0             20h
kube-system   cilium-envoy-z285h                                         1/1     Running   0             20h
kube-system   cilium-kht9j                                               1/1     Running   0             20h
kube-system   cilium-n7g9d                                               1/1     Running   0             20h
kube-system   cilium-operator-84758dd9bd-dvffv                           1/1     Running   0             20h
kube-system   cilium-operator-84758dd9bd-g45gh                           1/1     Running   0             20h
kube-system   coredns-868954d896-ht8xz                                   1/1     Running   0             20h
kube-system   coredns-868954d896-qmqln                                   1/1     Running   0             20h
kube-system   csi-secrets-store-secrets-store-csi-driver-ckb4b           3/3     Running   0             20h
kube-system   csi-secrets-store-secrets-store-csi-driver-cqw4k           3/3     Running   0             20h
kube-system   csi-secrets-store-secrets-store-csi-driver-tfffp           3/3     Running   0             20h
kube-system   hubble-relay-587fd6785-g2f8q                               1/1     Running   1 (20h ago)   20h
kube-system   hubble-ui-f799d8b94-574lj                                  2/2     Running   0             20h
```

In general, a pod with `operator` in its name is an "SRE in a pod".
They automate deployment and self heal the cluster.

### Logging & Monitoring

Currently (4/6/26) we favour an external Grafana and tunneling into the K8s cluster.
But Grafana (`kube-prometheus-stack-grafana-6d75dbdd5c-l4nvp`) is deployed as part of the Prometheus Helm chart.
So is the Alert Manager (`alertmanager-kube-prometheus-stack-alertmanager-0`).
It too is currently unused.

The Hubble UI (`hubble-ui-f799d8b94-574lj`) shows connections that are in Cilium's (finite) memory.
Therefore, this GUI represents connection in a limited window in time not all connections ever made by the pods.
The window of time depends on traffic volumes, available memory etc.

Hubble relay (`hubble-relay-587fd6785-g2f8q`) collects the data from all the nodes and serves it.

### Networking

Cilium (`cilium-`UUID) only deals with L3/L4 traffic (IP/TCP etc). 
Cilium Envoy (`cilium-envoy-*`) deals with higher level traffic (including HTTP).
It allows network policies to be set at this level.


