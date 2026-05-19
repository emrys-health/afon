# Monitoring

To install the monitoring components on AWS, run:

```shell
mvn compile -Dexec.mainClass=health.emrys.afon.cdk.PrometheusClusterDeploy exec:java
```

and `tofu apply` the file it generates in `cdktf.out/stacks/aws`.
This installs Prometheus, Hubble etc.

### Prometheus

Tunnel into the Kubernetes cluster with:

```shell
docker run -d --name grafana -p 3000:3000 --network=host grafana/grafana
kubectl port-forward  svc/kube-prometheus-stack-prometheus 9090:9090
```

then point your browser at `http://localhost:3000` and log into Grafana with admin/admin.
Add a Data Source that's `http://localhost:9090` and you should see nearly 2000 metrics.

### Hubble

Install Hubble then tunnel into Kubernetes with:

```shell
kubectl port-forward svc/hubble-ui -n kube-system 12000:80
```

then point your browser at `http://localhost:12000` and you should see the network flows between pods.

To give something akin to `netstat` in Linux, create yet another tunnel with:

```shell
kubectl -n kube-system port-forward svc/hubble-relay 4245:80
```

and run `hubble observe`.