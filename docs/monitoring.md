# Monitoring

To install the monitoring components on AWS:

- Generate config files and executes tofu:

```shell
./afon.sh -a exec --exec-class health.emrys.afon.cdk.aws.PrometheusClusterDeployment
```

The tofu/terraform executions can be done directly via:
```shell
cd cdktf.out/stacks/aws
tofu apply --auto-approve
```

This installs all the tools (prometheus, etc ...).

### Tools

We currently have:

- prometheus: running in the cloud to capture metrics 
- loki: running in the cloud to view logs. Note that loki writes logs to S3 (see below)
- grafana: running locally to view metrics and logs
- hubble: running in the cloud to view network traffic
- hubble-cli: running locally to query hubble

### Kube port forwarding

Direct invocation of `kubectl portforward` may be problematic in some envs. 

Install [kubefwd](https://kubefwd.com) 

```shell
sudo -E kubefwd svc -n default --tui
```

### Prometheus

Have Grafana tunnel into the Kubernetes cluster with:

```shell
docker run -d --name grafana -p 3000:3000 grafana/grafana 

kubectl port-forward  svc/kube-prometheus-stack-prometheus 9090:9090
```

Point a browser at `http://localhost:3000` and log into Grafana with `admin`/`admin`.
Add Data Source `http://localhost:9090` 
One should see nearly 2000 metrics.

NOTE:  If getting connection refused when adding the data source, use http://host.docker.internal:9090 in place of http://localhost:9090 (see https://github.com/grafana/grafana/issues/46434)


### Hubble

Tunnel into Kubernetes with:

```shell
kubectl port-forward svc/hubble-ui -n kube-system 12000:80
```

Point a browser at `http://localhost:12000` 
One should see the network flows between pods.

![image](pics/hubble-ui.png)

To give something akin to `netstat` in Linux, create yet another tunnel with:

```shell
kubectl -n kube-system port-forward svc/hubble-relay 4245:80
```

Install the [Hubble CLI](https://docs.cilium.io/en/stable/observability/hubble/setup/)

Run `hubble observe`. This will print out something like:

```shell
$ hubble observe
Jun  1 09:05:34.677: 10.0.1.196:40327 (host) -> 18.169.47.27:443 (world) to-network FORWARDED (TCP Flags: ACK, PSH)
Jun  1 09:05:35.097: 10.0.1.196:46270 (host) -> 18.169.47.27:443 (world) to-network FORWARDED (TCP Flags: ACK)
Jun  1 09:05:36.323: 10.0.2.139 (remote-node) -> 10.0.1.24 (health) to-endpoint FORWARDED (ICMPv4 EchoRequest)
Jun  1 09:05:36.323: 10.0.2.139 (remote-node) <- 10.0.1.24 (health) to-network FORWARDED (ICMPv4 EchoReply)
Jun  1 09:05:36.323: 10.0.2.139:55266 (remote-node) -> 10.0.1.24:4240 (health) to-endpoint FORWARDED (TCP Flags: ACK, PSH)
Jun  1 09:05:36.324: 10.0.2.139:55266 (remote-node) <- 10.0.1.24:4240 (health) to-network FORWARDED (TCP Flags: ACK, PSH)
...
```

### Loki

Tunnel into the K8s to allow your local Grafana to show logs by running:

```shell
kubectl port-forward svc/loki 3100:3100
```

Then (if you have not already), add a `loki` Data Source that points at `http://localhost:3100`.
Note that you'll need to add an `HTTP headers` value with a header name of `X-Scope-OrgID`
and a value that's whatever `CLUSTER_NAME` is in `afon.properties`.

![image](pics/grafana_multitenancy.png)

You should now be able to logs in `Drilldown -> Logs` in the Grafana GUI.
You may need to refresh your browser.

Loki persists its logs to the bucket defined by `AWS_S3_BUCKET_NAME` in `afon-aws.properties`.

### Blob storage size

AWS: 
```shell
aws s3 ls s3://emrys-afon-bucket/acceptancetests/ --recursive --summarize | grep "Total Size
```

GCP:
```shell
gcloud storage du -s gs://emrys_afon_core/acceptancetests/**
```

Azure:
```shell
az storage blob list   --account-name afonstorageaccount   --container-name afonstorage  --prefix acceptancetests/   --query "[].properties.contentLength"   --output tsv | awk '{sum+=$1} END {printf "%.2f kb\n", sum/1024}'
```

# Local Grafana

You can run your Dockerized Grafana persisting the config each time with:
```shell
docker run -d --name grafana --network=host -v $YOUR_LOCAL_CONFIG_DIRECTORY/grafana:/var/lib/grafana grafana/grafana
```
To grab all the metadata files first time around, run:
```shell
docker cp YOUR_CONTAINER_ID:/var/lib/grafana $YOUR_LOCAL_CONFIG_DIRECTORY
```
**Note** you may need to execute `sudo chown -R 472:472 YOUR_LOCAL_CONFIG_DIRECTORY` to give Grafana permissions.
User/group 472 is the Grafana user.

![image](pics/grafana.png)