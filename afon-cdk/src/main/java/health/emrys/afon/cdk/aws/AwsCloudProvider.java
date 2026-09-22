package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_role.DataAwsIamRole;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_role.DataAwsIamRoleConfig;
import com.hashicorp.cdktf.providers.aws.data_aws_vpc.DataAwsVpc;
import com.hashicorp.cdktf.providers.aws.data_aws_vpc.DataAwsVpcConfig;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksCluster;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksClusterConfig;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster_auth.DataAwsEksClusterAuth;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_policy.DataAwsIamPolicy;
import com.hashicorp.cdktf.providers.aws.data_aws_lb.DataAwsLb;
import com.hashicorp.cdktf.providers.aws.data_aws_lb.DataAwsLbConfig;
import com.hashicorp.cdktf.providers.aws.eks_access_entry.EksAccessEntry;
import com.hashicorp.cdktf.providers.aws.eks_access_entry.EksAccessEntryConfig;
import com.hashicorp.cdktf.providers.aws.eks_access_policy_association.EksAccessPolicyAssociation;
import com.hashicorp.cdktf.providers.aws.eks_access_policy_association.EksAccessPolicyAssociationAccessScope;
import com.hashicorp.cdktf.providers.aws.eks_access_policy_association.EksAccessPolicyAssociationConfig;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksCluster;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterAccessConfig;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterVpcConfig;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroup;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroupScalingConfig;
import com.hashicorp.cdktf.providers.aws.iam_openid_connect_provider.IamOpenidConnectProvider;
import com.hashicorp.cdktf.providers.aws.iam_openid_connect_provider.IamOpenidConnectProviderConfig;
import com.hashicorp.cdktf.providers.aws.iam_policy.IamPolicy;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRoleConfig;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachment;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachmentConfig;
import com.hashicorp.cdktf.providers.aws.internet_gateway.InternetGateway;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53Record;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53RecordAlias;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53RecordConfig;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTable;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTableRoute;
import com.hashicorp.cdktf.providers.aws.route_table_association.RouteTableAssociation;
import com.hashicorp.cdktf.providers.aws.secretsmanager_secret.SecretsmanagerSecret;
import com.hashicorp.cdktf.providers.aws.subnet.Subnet;
import com.hashicorp.cdktf.providers.aws.vpc.Vpc;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.cdk.AbstractCloudProvider;
import health.emrys.afon.cdk.JupyterHubConfig;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.constructs.Construct;

import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkHelper.*;
import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static health.emrys.afon.cdk.PolarisConfig.POLARIS_INTERNAL;
import static health.emrys.afon.config.AfonProperty.AWS_ACCOUNT_ID;
import static health.emrys.afon.config.AfonProperty.AWS_VPC_ID;
import static health.emrys.afon.config.AfonProperty.AWS_PRIVATE_SUBNET_IDS;
import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_DNS_ZONE;
import static java.lang.String.format;

public class AwsCloudProvider extends AbstractCloudProvider {

    private static final Logger log = LoggerFactory.getLogger(AwsCloudProvider.class);

    private final AwsCleanupHelper           cleanupHelper;
    private final AwsComponentNames          componentNames;
    private final String                     polarisDomainName;
    private final String                     accountId;
    private final String                     region;
    private final String                     clusterName;
    private final String                     clusterPolicyArn;
    private final String                     polarisCertArn;
    // HDRS (Patch 1): data-source lookups of the pre-existing cluster/network
    // built by hdrsuk/infra-aws-poc, replacing the resources upstream created.
    private final DataAwsEksCluster          cluster;
    private final List<ITerraformDependable> eksDependOn;
    private final List<String>               subnetIds;
    final         DataAwsEksClusterAuth      eksAuthData;
    final         DataAwsIamRole             loadBalancerRole;
    final         Release                    lbRelease;
    final         DataAwsVpc                 vpc;

    AwsCloudProvider(final Construct scope, final String id, AfonPropertyProvider properties) {
        super(scope, id);
        cleanupHelper = new AwsCleanupHelper(properties);
        componentNames = new AwsComponentNames(properties);
        clusterName    = properties.getValue(AfonProperty.CLUSTER_NAME);
        polarisCertArn = properties.getValue(AfonProperty.AWS_POLARIS_CERT_ARN);

        this.polarisDomainName = properties.getValue(AfonProperty.AWS_POLARIS_DOMAIN_NAME);
        this.accountId         = properties.getValue(AWS_ACCOUNT_ID);
        this.region            = properties.getValue(AfonProperty.AWS_REGION);
        clusterPolicyArn       = format("arn:aws:iam::%s:policy/AWSLoadBalancerControllerIAMPolicyCurl", accountId);
        // --- AWS Provider Configuration (Required for EKS Addon) ---
        // 1. Configure the AWS Provider
        AwsProvider.Builder.create(this, "aws")
                           .region(region) // Set your desired AWS Region
                           .build();


        // --- 1. SECRETS MANAGER CREATION ---

        // Create the secret container
        final var applicationSecret = SecretsmanagerSecret.Builder
            .create(this, "ApplicationSecret")
            .name(componentNames.getSecret())
            .description("Secret containing application " +
                             "configuration or API keys for EKS.")
            .tags(Map.of("Name", "EKSAppConfig"))
            .recoveryWindowInDays(0)
            .build();

        // HDRS (Patch 1): everything from here to the Cilium release is a
        // LOOKUP of infrastructure built by hdrsuk/infra-aws-poc. Upstream
        // created a VPC (10.0.0.0/16, public subnets), an EKS cluster + node
        // group, their IAM roles, an IRSA OIDC provider, cluster-admin for the
        // account root, and rewrote ~/.kube/config - all removed. The subnet
        // list is the PRIVATE subnets: every ALB/LB here is internal (Patch 7).
        vpc = new DataAwsVpc(this, "eksVpc", DataAwsVpcConfig
            .builder()
            .id(properties.getValue(AWS_VPC_ID))
            .build());

        subnetIds = Arrays.stream(properties.getValue(AWS_PRIVATE_SUBNET_IDS).split(","))
                          .map(String::trim)
                          .collect(Collectors.toList());

        cluster = DataAwsEksCluster.Builder
            .create(this, "eksCluster")
            .name(clusterName)
            .build();

        // if one depends_on this, then the cluster should be running
        eksAuthData = DataAwsEksClusterAuth.Builder
            .create(this, "eksClusterAuthData")
            .name(clusterName)
            .build();

        // Role created (with the LBC policy already attached) by infra-aws-poc
        // under the exact name upstream used, so only this lookup changes.
        loadBalancerRole = new DataAwsIamRole(
            scope, "AmazonEKSLoadBalancerControllerRole",
            DataAwsIamRoleConfig
                .builder()
                .name(componentNames.getLoadBalancerRoleName())
                .build());
        String clusterEndpoint = cluster.getEndpoint();
        String apiHost         = Fn.replace(clusterEndpoint, "https://", "");

        // Basically, EKS assumes you want a few components that you don't need if you use Cilium
        // vpc-cni addon removed: Cilium ENI mode is a complete replacement for vpc-cni.
        // Keeping the EKS addon caused EKS to continuously overwrite Cilium's no-op patch
        // to aws-node (resolveConflictsOnUpdate=OVERWRITE), breaking pod internet connectivity.
        List<LocalExecProvisioner> onCiliumCreation = uponCreateRun(
            "kubectl delete ds aws-node -n kube-system  || echo no aws-node",
            "kubectl delete ds kube-proxy -n kube-system || echo no kube-proxy",
            format(
                "aws eks delete-addon --cluster-name %s --addon-name vpc-cni || " +
                    "echo no vpc-cni", clusterName),
            format(
                "aws eks delete-addon --cluster-name %s --addon-name kube-proxy ||" +
                    " echo no kube-proxy", clusterName));
        // Both Cilium releases install from a chart vendored into the repo rather than
        // pulling from https://helm.cilium.io/ on every apply. See CdkUtils#vendoredChart.
        String ciliumChart = vendoredChart(this, "ciliumChart", "cilium-1.15.0.tgz");
        Release cilium = Release.Builder
            .create(this, "cilium")
            .name("cilium")
            .chart(ciliumChart)
            .namespace("kube-system")
            .dependsOn(List.of(cluster, eksAuthData))
            .provisioners(onCiliumCreation)
            // No version() — the vendored tarball is the version, and the provider reads it
            // from the chart's own Chart.yaml.
            // Set the specific values to kill kube-proxy dependency
            .set(java.util.List.of(
                // 1. Connectivity to EKS Control Plane
                createValue("kubeProxyReplacement", "true"),
                createValue("k8sServiceHost", apiHost),
                createValue("k8sServicePort", "443"),

                // 2. EKS-Specific Networking (ENI Mode)
                createValue("eni.enabled", "true"),
                createValue("ipam.mode", "eni"),
                createValue("routingMode", "native"),
                // BPF masquerade is required: kubeProxyReplacement uses bpf_redirect
                // to forward pod egress directly to the ENI, bypassing iptables
                // POSTROUTING — so iptables MASQUERADE never fires.
                createValue("bpf.masquerade", "true"),
                createValue("enableIPv4Masquerade", "true"),
                createValue("ipv4NativeRoutingCIDR", vpc.getCidrBlock()),

                // FQDN-based network policy (L7 DNS): toEndpoints/toFQDNs rules need
                // the DNS proxy, which is gated by the cilium-config key
                // `enable-fqdn-policy`. The chart writes arbitrary ConfigMap keys via
                // the `extraConfig` map, so set it there.
                createStringValue("extraConfig.enable-fqdn-policy", "true"),

                // 3. Solving the "Pending" Issue (Tolerations)
                // This allows Cilium to run even if the node is "NotReady"
                createValue("tolerations[0].operator", "Exists"),
                createValue("tolerations[0].effect", "NoSchedule"),

                // Also apply to the operator so it can manage the pods
                createValue("operator.tolerations[0].operator", "Exists"),
                createValue("operator.tolerations[0].effect", "NoSchedule"),

                // 4. Prometheus metrics endpoints.
                // - cilium-agent: /metrics on container port 9962
                // - cilium-operator: /metrics on container port 9963
                // - cilium-envoy:   /metrics on container port 9964
                // The matching ServiceMonitor toggles (prometheus.serviceMonitor.enabled,
                // operator.prometheus.serviceMonitor.enabled) are intentionally LEFT OFF:
                // they need monitoring.coreos.com CRDs which kube-prometheus-stack
                // installs in a later stack (AwsFullStack). The ServiceMonitor
                // for Cilium is therefore created on the Prometheus side once those CRDs
                // exist.
                createValue("prometheus.enabled", "true"),
                createValue("operator.prometheus.enabled", "true"),

                // 5. Standalone cilium-envoy DaemonSet (L7 proxy). Default in 1.15 runs
                // envoy embedded in the cilium-agent container; enabling this splits it
                // out so agent restarts don't drop L7 connections. envoy.prometheus is
                // on by default in 1.15 — set explicitly for clarity.
                createValue("envoy.enabled", "true"),
                createValue("envoy.prometheus.enabled", "true"),
                // envoy is scheduled on every node like the agent, so it needs the same
                // permissive tolerations (otherwise it can't start on NotReady nodes).
                createValue("envoy.tolerations[0].operator", "Exists"),
                createValue("envoy.tolerations[0].effect", "NoSchedule"),

                // 6. Hubble — network observability (flow visibility + metrics).
                // - hubble.enabled: on-node flow collector inside cilium-agent
                // - hubble.relay: aggregates flows from all nodes (gRPC :4245)
                // - hubble.ui:    web UI for browsing flows; consumes relay
                // hubble-metrics Service is created automatically when at least one
                // metric category is enabled; it exposes /metrics on port 9965 from
                // each cilium-agent pod (port name `hubble-metrics`). The matching
                // ServiceMonitor is created on the Prometheus side, same reason as
                // the agent/operator/envoy ones above (CRD ordering).
                createValue("hubble.enabled", "true"),
                // Default cert issuer (auto.method=helm) bakes certs into the
                // helm release and has been observed to leave cilium-agent's
                // hubble TLS listener silent on EKS + kubeProxyReplacement,
                // so hubble-relay's dial to hubble-peer:443 hangs and the
                // startup probe trips. The cronJob issuer regenerates the
                // CA + serving certs from inside the cluster and keeps them
                // rotated, which clears the handshake stall.
                createValue("hubble.tls.auto.method", "cronJob"),
                createValue("hubble.relay.enabled", "true"),
                // Cold-start dials of hubble-peer can take longer than the
                // 5s default while BPF socket-LB warms up after a fresh
                // Cilium rollout — give the peer client enough headroom to
                // finish the handshake before the 5-min startup-probe budget
                // is consumed.
                createValue("hubble.relay.dialTimeout", "30s"),
                // hubble-relay's own /metrics endpoint (port 9966, name `metrics`)
                // exposed via the hubble-relay-metrics Service in kube-system.
                // ServiceMonitor for it is created on the Prometheus side, same
                // CRD-ordering reason as the agent/operator/envoy/hubble monitors.
                createValue("hubble.relay.prometheus.enabled", "true"),
                // hubble-ui is intentionally NOT enabled here. It ships as a
                // separate standalone release (see below) so Terraform can
                // order it after the datapath is Ready and its hardcoded,
                // no-grace frontend liveness probe doesn't trip on cold start.
                createValue("hubble.ui.enabled", "false"),
                createValue("hubble.metrics.enableOpenMetrics", "true"),
                createValue("hubble.metrics.enabled[0]", "dns"),
                createValue("hubble.metrics.enabled[1]", "drop"),
                createValue("hubble.metrics.enabled[2]", "tcp"),
                createValue("hubble.metrics.enabled[3]", "flow"),
                createValue("hubble.metrics.enabled[4]", "port-distribution"),
                createValue("hubble.metrics.enabled[5]", "icmp"),
                createValue("hubble.metrics.enabled[6]", "httpV2")
                                  ))
            .build();

        eksDependOn = List.of(cluster, eksAuthData, cilium);

        // Gate: block until the Cilium datapath is actually Ready (the main
        // release above is not atomic, so its completion does not imply the
        // agent DaemonSet has rolled out). The relay wait is best-effort — the
        // UI frontend probe only needs pod networking, not relay — but waiting
        // for it means the backend has something to talk to on first start.
        Resource ciliumReady = Resource.Builder
            .create(this, "ciliumDatapathReady")
            .dependsOn(List.of(cilium))
            .provisioners(uponCreateRun(
                "kubectl -n kube-system rollout status daemonset/cilium --timeout=300s",
                "kubectl -n kube-system rollout status deployment/hubble-relay --timeout=300s || true"
            ))
            .build();

        // hubble-ui as its own release, gated on ciliumReady. The frontend's
        // hardcoded liveness probe on :8081 has no startup grace, so when it
        // was bundled in the Cilium release it got killed into CrashLoopBackoff
        // while the agent was still programming the node. Starting it only
        // after the datapath is Ready means :8081 is reachable immediately and
        // the probe passes first time — no restart needed. agent/operator are
        // off so this release renders hubble-ui only; hubble TLS is off because
        // relay's server TLS is disabled (UI talks to hubble-relay:80 in
        // plaintext), so no client-cert volume is required.
        Release.Builder
            .create(this, "hubble-ui")
            .name("hubble-ui")
            .chart(ciliumChart)
            .namespace("kube-system")
            .dependsOn(List.of(ciliumReady))
            .set(java.util.List.of(
                createValue("agent", "false"),
                createValue("operator.enabled", "false"),
                createValue("hubble.enabled", "true"),
                createValue("hubble.tls.enabled", "false"),
                createValue("hubble.ui.enabled", "true"),
                createValue("hubble.ui.standalone.enabled", "true")
            ))
            .build();

        // metrics-server — serves the Kubernetes Metrics API (metrics.k8s.io)
        // that backs `kubectl top nodes` and `kubectl top pods`. EKS does not
        // install it by default, so without this release those commands fail
        // with "error: Metrics API not available". Gated on ciliumReady because
        // metrics-server scrapes every kubelet over the pod network, which only
        // works once the Cilium datapath has rolled out.
        Release metricsServer = Release.Builder
            .create(this, "metrics-server")
            .name("metrics-server")
            .chart(vendoredChart(this, "metricsServerChart", "metrics-server-3.12.2.tgz"))
            .namespace("kube-system")
            .dependsOn(List.of(ciliumReady))
            .timeout(300)
            .atomic(true)
            .set(List.of(
                // EKS kubelets serve their summary API with self-signed certs
                // that are not part of the cluster CA, so metrics-server cannot
                // verify them and the scrape fails with an x509 error. Skipping
                // verification of the kubelet serving cert is the standard EKS
                // workaround. (The chart's default args already prefer the node
                // InternalIP, which is what we want here.)
                createValue("args[0]", "--kubelet-insecure-tls")
            ))
            .build();

        // Confirm the Metrics API is actually serving before the stack reports
        // done. metrics-server needs a scrape cycle (~15-60s) after rollout
        // before `kubectl top nodes` returns data rather than
        // "metrics not available yet", so retry briefly. Uses the local
        // kubeconfig written by newKubeConfig, same as the ciliumReady gate.
        Resource.Builder
            .create(this, "metricsServerReady")
            .dependsOn(List.of(metricsServer))
            .provisioners(uponCreateRun(
                "kubectl -n kube-system rollout status deployment/metrics-server --timeout=300s",
                "for i in $(seq 1 30); do kubectl top nodes && break || " +
                    "{ echo 'waiting for node metrics...'; sleep 10; }; done"
            ))
            .build();

        // HDRS Patch 20: Tetragon, the exec sensor the audit platform reads.
        //
        // ON BY DEFAULT, and the default is the decision rather than a
        // convenience. This is the HDRS fork of afon: a TRE adopting it is
        // adopting the audit platform with it, so collecting what a researcher
        // ran is part of what afon *is* here, not a feature to remember to
        // switch on. The gates on the perimeter sidecar (AUDIT_SIDECAR_IMAGE)
        // are shaped the other way round because they need an image reference
        // nobody can guess; this needs nothing, so it needs no excuse to be off.
        //
        // Set AUDIT_EXEC_SENSOR_ENABLED=false to deploy without it - a real
        // escape hatch for a cluster owner who will not accept a privileged
        // cluster-wide DaemonSet, and a deliberate act with a name.
        boolean execSensorEnabled = Boolean.parseBoolean(
            properties.getValue(AfonProperty.AUDIT_EXEC_SENSOR_ENABLED, "true"));
        if (execSensorEnabled) {
            // NOT a configurable value. This is afon's own singleuser pod label,
            // from the class that stamps it - see JupyterHubConfig#singleuserSelector.
            String execSensorSelector = JupyterHubConfig.singleuserSelector();
            String exportDir = properties.getValue(
                AfonProperty.AUDIT_EXEC_SENSOR_EXPORT_DIR, "/var/run/cilium/tetragon");
            Release.Builder
                .create(this, "tetragon")
                .name("tetragon")
                .chart(vendoredChart(this, "tetragonChart", "tetragon-1.7.1.tgz"))
                .namespace("kube-system")
                // Tetragon is independent of the CNI - it would work under Calico
                // - but it is gated on ciliumReady anyway, because its own pods
                // need a working datapath to schedule and report.
                .dependsOn(List.of(ciliumReady))
                .timeout(300)
                .atomic(true)
                // values rather than set: the allowlist below is a JSON document
                // full of commas, dots and an `=`, and Helm's --set parsing would
                // shred all three.
                .values(List.of(tetragonValues(execSensorSelector, exportDir)))
                .build();
        }

        // HDRS (Patch 1): the LBC role, its trust policy and the cluster-role
        // policy attachment are all removed - infra-aws-poc creates the role
        // (with the LBC policy already attached) under the name upstream
        // expects, and it is looked up as a data source above.

        lbRelease = configureIngress(scope, loadBalancerRole);

        IngressV1 ingress = createHttpsIngress(
            "polaris-https-ingress",
            "polaris-ingress",
            polarisCertArn,
            this.polarisDomainName,
            POLARIS_INTERNAL,
            8181,
            subnetIds,
            // Quarkus readiness, which includes the datasource check - the one that read
            // DOWN for three days while nothing noticed. Served on 8182, not the API port.
            "/q/health/ready",
            // The port NAME, not 8182. With instance target type the controller resolves
            // a name to that service port's NodePort (30401 here, and dynamic); a bare
            // number is used literally against the node, where nothing is listening.
            "management"
                                              );


        // Destroy-time cleanup: must run before lbRelease is uninstalled so the LBC
        // webhook doesn't block Helm and TargetGroupBinding finalizers can be removed.
        // The explicit helm uninstall --no-hooks pre-empts Terraform's own Helm destroy,
        // which fails when webhooks/finalizers interfere. Helm treats "release not found" as success.
        List<LocalExecProvisioner> onLbcDestroy = uponDestroyRun(
            "kubectl get validatingwebhookconfigurations -o name 2>/dev/null | grep aws-load-balancer" +
                " | xargs -r kubectl delete --ignore-not-found=true",
            "kubectl get mutatingwebhookconfigurations -o name 2>/dev/null | grep aws-load-balancer |" +
                " xargs -r kubectl delete --ignore-not-found=true",
            "kubectl get targetgroupbindings -A --no-headers 2>/dev/null | awk '{print $1, $2}' | " +
                "while read NS NAME; do kubectl patch targetgroupbinding \"$NAME\" -n \"$NS\" -p " +
                "'{\"metadata\":{\"finalizers\":[]}}' --type=merge 2>/dev/null || true; done",
            "kubectl patch ingress polaris-ingress -n default -p '{\"metadata\":{\"finalizers\":[]}}'" +
                " --type=merge 2>/dev/null || true",
            "helm uninstall aws-load-balancer-controller-chart -n kube-system --no-hooks 2>/dev/null " +
                "|| true",
            "kubectl delete secret sh.helm.release.v1.aws-load-balancer-controller-chart.v1 -n kube-system || true");
        Resource.Builder
            .create(this, "lbc-destroy-cleanup")
            .dependsOn(List.of(ingress, lbRelease))
            .provisioners(onLbcDestroy)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();

        // 2. Create the Alias A Record
        // An Alias record is used to point a domain name at an AWS resource (like an ELB/ALB).
        // For the name, we use the specific subdomain (e.g., "app.mydomain.com").
        configureDNS("IngressAliasRecord",
                     properties.getValue(AWS_POLARIS_DNS_ZONE),
                     this.polarisDomainName,
                     lbRelease,
                     ingress,
                     "polaris-ingress");
    }

    private static ReleaseSet createValue(String name, String value) {
        return ReleaseSet.builder()
                         .name(name)
                         .value(value)
                         .build();
    }

    /**
     * Helm values for the Tetragon release (HDRS Patch 20).
     *
     * <p>Tetragon reports every process that execs on the node. What the audit platform is
     * entitled to observe is what a researcher ran in their own workspace, and nothing else on a
     * cluster that also runs JupyterHub, Guacamole, Polaris, Spark and Trino. Everything below is
     * about keeping those two things the same set.
     *
     * @param selector  Kubernetes label selector scoping the export - always
     *                  {@link JupyterHubConfig#singleuserSelector()}, never a configured value.
     *                  Tetragon's {@code Filter.labels} field takes label-selector syntax.
     * @param exportDir directory on the node the JSON export is written to; the audit platform's
     *                  node agent mounts exactly this path.
     */
    static String tetragonValues(String selector, String exportDir) {
        return String.format("""
            # Where the JSON export is written on the node. Top-level in this
            # chart, unlike everything under `tetragon:` below - and its default
            # is already /var/run/cilium/tetragon, which is exactly the path the
            # audit platform's node agent mounts and tails. Set explicitly anyway,
            # because it is a contract between two repositories and a default that
            # silently changes on a chart bump would break collection with nothing
            # to read.
            exportDirectory: "%2$s"

            export:
              # No stdout exporter, and this is not a preference.
              #
              # The chart's default is "stdout" (its own comment: `"" to disable`),
              # which adds a sidecar tailing the export to the container's stdout.
              # promtail ships every container's stdout to Loki on this cluster, so
              # leaving the default would copy every exec event from every
              # researcher's workspace into Loki as a side effect - a store with
              # different retention and different access control from the audit
              # store. The file is written either way: tetragon_configmap.yaml sets
              # export-filename whenever tetragon.exportFilename is non-empty, and
              # the export-logs hostPath in daemonset.yaml is unconditional.
              mode: ""

            tetragon:
              # Non-empty is what turns the file export on at all.
              exportFilename: tetragon.log

              # Default-deny. Tetragon's export filters are an allowlist, and once
              # any allowlist exists only what it names is exported - so this one
              # line is the whole scope of what leaves the node.
              #
              # `labels` filters on POD labels in Kubernetes label-selector syntax
              # (Filter.labels, field 9 of the events proto). Namespace alone would
              # be wrong: afon runs in `default` beside JupyterHub, Guacamole,
              # Polaris, Spark and Trino, whose execs are not researcher activity.
              #
              # PROCESS_EXEC only. The chart's default also carries PROCESS_EXIT,
              # PROCESS_KPROBE, PROCESS_UPROBE, PROCESS_TRACEPOINT and PROCESS_LSM;
              # the node agent parses none of them, and an event nobody reads is a
              # file on a node growing for no reason.
              exportAllowList: |-
                {"event_set":["PROCESS_EXEC"],"labels":["%1$s"]}

              # The chart's default denylist, restated rather than inherited,
              # because a denylist takes precedence over the allowlist above and a
              # reader should not have to go and look up what is being dropped
              # silently. Health checks are noise; the three namespaces are
              # infrastructure the allowlist would not have matched anyway.
              exportDenyList: |-
                {"health_check":true}
                {"namespace":["", "cilium", "kube-system"]}

              # Rotation, at the chart's own defaults. Restated because these
              # numbers bound how much of what researchers ran sits in a file on a
              # node between the agent's reads. The agent's follow() reopens on
              # truncation or an inode change, so rotation is safe for it.
              exportFileMaxSizeMB: 10
              exportFileMaxBackups: 5
              exportFilePerm: "600"
            """, selector, exportDir);
    }

    /**
     * Like {@link #createValue} but forces Helm to treat the value as a string
     * (equivalent to {@code --set-string}). Needed for values that land verbatim in
     * a ConfigMap's {@code data}, whose entries must be strings: an unquoted {@code true}
     * renders as a YAML bool and Kubernetes then rejects the ConfigMap.
     */
    private static ReleaseSet createStringValue(String name, String value) {
        return ReleaseSet.builder()
                         .name(name)
                         .value(value)
                         .type("string")
                         .build();
    }

    /**
     * Creates a Route 53 Alias A record pointing {@code domainName} at the ALB the AWS Load
     * Balancer Controller provisions for the given Ingress. The ALB is located via an
     * {@code aws_lb} data source filtered on the controller's tags (rather than reading the
     * Ingress' status, which the kubernetes provider returns as null unreliably), and the
     * ALB's own DNS name and hosted zone are used for the alias.
     *
     * @param constructId   unique CDKTF construct ID for this record
     * @param r53ZoneId     the Route 53 hosted zone ID that owns {@code domainName}
     * @param domainName    the domain to point at the ALB (e.g. {@code "emrysjupyter.click"})
     * @param release       Helm release the record should depend on
     * @param ingress       IngressV1 the data-source lookup depends on (so it resolves at apply time)
     * @param ingressK8sName the Ingress' Kubernetes metadata.name, used to match the ALB's
     *                       {@code ingress.k8s.aws/stack=default/<name>} tag
     * @return the alias record, so callers can make workloads that resolve {@code domainName}
     *         depend on it rather than racing its creation
     */
    public Route53Record configureDNS(String constructId, String r53ZoneId, String domainName,
                                      Release release, IngressV1 ingress, String ingressK8sName) {
        // We deliberately do NOT read the ALB hostname from the Ingress' status. The
        // hashicorp/kubernetes provider routinely returns a null status[0] for an
        // ALB-backed Ingress even when wait_for_load_balancer is set and the ALB is
        // actually up (see kubernetes-sigs/aws-load-balancer-controller#2768 and
        // hashicorp/terraform-provider-kubernetes#2679), which made the Route53 alias
        // below fail with "Attempt to index null value".
        //
        // The AWS Load Balancer Controller does provision the ALB; only the status
        // read-back is unreliable. So we query AWS directly for the ALB the controller
        // created, identified by the tags it stamps on it. depends_on(ingress) defers
        // this data read to apply time, after the Ingress (and thus the ALB) exists.
        DataAwsLb alb = new DataAwsLb(
            this, constructId + "Alb", DataAwsLbConfig
            .builder()
            .tags(Map.of(
                "elbv2.k8s.aws/cluster", clusterName,
                "ingress.k8s.aws/stack", "default/" + ingressK8sName,
                "ingress.k8s.aws/resource", "LoadBalancer"))
            .dependsOn(List.of(ingress))
            .build());
        return new Route53Record(
            this, constructId, Route53RecordConfig
            .builder()
            .zoneId(r53ZoneId)
            .name(domainName)
            .type("A")
            .allowOverwrite(true)
            .dependsOn(List.of(release))
            .alias(Route53RecordAlias
                       .builder()
                       .name(alb.getDnsName())
                       .zoneId(alb.getZoneId())
                       .evaluateTargetHealth(true)
                       .build())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build());
    }

    private Release configureIngress(Construct scope, DataAwsIamRole loadBalancerRole) {
        String serviceAccountName = configureServiceAccount(scope, loadBalancerRole);

        return helmInstallLoadBalancer(serviceAccountName, loadBalancerRole);
    }

    private @NonNull String configureServiceAccount(Construct scope, DataAwsIamRole loadBalancerRole) {
        String serviceAccountName = "aws-load-balancer-controller";

        Map<String, String> labels = Map.of(
            "app.kubernetes.io/component", "controller",
            "app.kubernetes.io/instance", "aws-load-balancer" +
                "-controller",
            "app.kubernetes.io/name", "aws-load-balancer" +
                "-controller"
                                           );
        Map<String, String> annotations = Map.of(
            "eks.amazonaws.com/role-arn",
            loadBalancerRole.getArn()
                                                );
        ServiceAccount.Builder
            .create(scope, serviceAccountName)
            .metadata(ServiceAccountMetadata
                          .builder()
                          .name(serviceAccountName)
                          .namespace("kube-system")
                          .labels(labels)
                          .annotations(annotations)
                          .build())
            .provisioners(uponDestroyRun(
                NO_INSTALLATION_FINALIZERS,
                NO_JUPYTER_LB_FINALIZERS,
                NO_POLARIS_FINALIZERS,
                NO_JUPYTER_FINALIZERS,
                NO_KEYCLOAK_FINALIZERS))
            .build();
        return serviceAccountName;
    }

    // HDRS (Patch 1): associateOIDCProvider removed - the IRSA OIDC provider
    // already exists (created by infra-aws-poc); creating it again would
    // fail with EntityAlreadyExists.

    private Release helmInstallLoadBalancer(String serviceAccountName, DataAwsIamRole loadBalancerRole) {
        ArrayList<ITerraformDependable> dependencies = new ArrayList(eksDependOn);
        dependencies.add(loadBalancerRole);
        return Release.Builder
            .create(this, "aws-load-balancer")
            .dependsOn(dependencies)
            .name(serviceAccountName + "-chart")
            .chart(vendoredChart(this, "loadBalancerChart", "aws-load-balancer-controller-1.17.0.tgz"))
            .namespace("kube-system")
            .timeout(600)
            .atomic(true)
            .set(List.of(
                ReleaseSet.builder().name("clusterName").value(clusterName).build(),
                ReleaseSet.builder().name("serviceAccount.create").value("false").build(),
                ReleaseSet.builder().name("vpcId").value(vpc.getId()).build(),
                ReleaseSet.builder().name("serviceAccount.name").value(serviceAccountName).build()
                        ))
            .build();
    }

    public IngressV1 createHttpsIngress(
        final String constructId,
        final String ingressName,
        final String certArn,
        final String hostName,
        final String serviceName,
        final int servicePort,
        final List<String> subnetIds) {
        return createHttpsIngress(constructId, ingressName, certArn, hostName, serviceName,
                                  servicePort, subnetIds, null, null);
    }

    /**
     * HDRS (Patch 20): as above, but with a real ALB health check.
     *
     * <p>Without {@code healthcheck-path} the controller applies the ALB default - GET
     * {@code /} expecting 200 - and none of this stack's three backends answers that:
     * Keycloak redirects (302), oauth2-proxy serves its sign-in page (403) and Polaris
     * has no route there (404). Every target group therefore reported every target
     * unhealthy from the day it was created, and traffic flowed only because an ALB
     * fails open when nothing in a group is healthy.
     *
     * <p>That is not a cosmetic problem. It meant a crash-looping backend was
     * indistinguishable from the steady state, so a dead Keycloak surfaced to users as
     * a browser 504 with nothing anywhere recording a change; and it left target-group
     * health useless as an alerting signal, pinned at zero healthy since day one.
     *
     * @param healthCheckPath path that returns 200 when the backend is well, or null to
     *                        keep upstream's (broken) default
     * @param healthCheckPort backend service port NAME to probe, or null for
     *                        {@code traffic-port}. Needed where health and traffic are
     *                        on different ports, as they are for Polaris. Use the name:
     *                        with instance target type the controller resolves it to the
     *                        service's NodePort, whereas a bare number is applied
     *                        literally to the node and will not be listening.
     */
    public IngressV1 createHttpsIngress(
        final String constructId,
        final String ingressName,
        final String certArn,
        final String hostName,
        final String serviceName,
        final int servicePort,
        final List<String> subnetIds,
        final String healthCheckPath,
        final String healthCheckPort) {
        KubernetesProvider kubernetesProvider = configureK8s();
        // The key annotations for the AWS Load Balancer Controller (LBC)
        String subnetIdsCSV = subnetIds.stream().collect(Collectors.joining(","));
        log.info("subnetIDs = {}, certArn = {}, hostname = {}", subnetIdsCSV, certArn, hostName);
        Map<String, String> albAnnotations = Map.of(
            // 1. Tell the LBC to create an Application Load Balancer (ALB)
            "kubernetes.io/ingress.class", "alb",

            // 2. CRITICAL: Specify the ARN of the ACM certificate
            "alb.ingress.kubernetes.io/certificate-arn", certArn,

            // 3. Configure the ALB to listen on both HTTP (80) and HTTPS (443)
            "alb.ingress.kubernetes.io/listen-ports", "[{\"HTTP\": 80}, {\"HTTPS\": 443}]",

            // 4. Force all HTTP traffic to redirect to HTTPS
            "alb.ingress.kubernetes.io/actions.ssl-redirect", "443",

            // 5. Specify that the traffic from the ALB to the EKS Pods is standard HTTP
            "alb.ingress.kubernetes.io/backend-protocol", "HTTP",

            // 6. HDRS: internal, never internet-facing - reachable over the
            // Client VPN only. Upstream sets "internet-facing" here.
            "alb.ingress.kubernetes.io/scheme", "internal",
            "external-dns.alpha.kubernetes.io/hostname", hostName,

            "alb.ingress.kubernetes.io/subnets", subnetIdsCSV
                                                   );

        // Ordered map: annotations are compared by key rather than position, so this is
        // not the Patch 19 problem, but there is no reason to render them at random.
        Map<String, String> annotations = new java.util.LinkedHashMap<>(albAnnotations);
        if (healthCheckPath != null) {
            annotations.put("alb.ingress.kubernetes.io/healthcheck-path", healthCheckPath);
            // Explicit rather than inherited: the default matcher is 200, but stating it
            // means a later upstream change to the default cannot silently widen this.
            annotations.put("alb.ingress.kubernetes.io/success-codes", "200");
            if (healthCheckPort != null) {
                annotations.put("alb.ingress.kubernetes.io/healthcheck-port", healthCheckPort);
            }
        }

        var sslServicePort = IngressV1SpecRuleHttpPathBackendService
            .builder()
            .name(serviceName)
            .port(IngressV1SpecRuleHttpPathBackendServicePort
                      .builder()
                      .number(servicePort)
                      .build()
                 ).build();
        IngressV1SpecRuleHttpPathBackend pathBackend = IngressV1SpecRuleHttpPathBackend
            .builder().service(sslServicePort).build();
        IngressV1SpecRuleHttpPath pathRule = IngressV1SpecRuleHttpPath
            .builder()
            .path("/")
            .pathType("Prefix")
            .backend(pathBackend)
            .build();
        IngressV1SpecRuleHttp servicePortRule = IngressV1SpecRuleHttp
            .builder()
            .path(Collections.singletonList(pathRule))
            .build();
        IngressV1SpecRule ingressRule = IngressV1SpecRule
            .builder()
            .host(hostName)
            .http(servicePortRule)
            .build();
        IngressV1Spec alb = IngressV1Spec
            .builder()
            .ingressClassName("alb")
            .rule(List.of(ingressRule))
            .build();
        IngressV1Metadata ingressMetadata = IngressV1Metadata
            .builder()
            .name(ingressName)
            .namespace("default")
            .annotations(annotations)
            .build();
        return IngressV1.Builder
            .create(this, constructId)
            .provider(kubernetesProvider)
            // The ALB (and thus the ingress' status.loadBalancer.hostname that the Route53
            // alias record reads) is only provisioned once the AWS Load Balancer Controller
            // is running. Without this dependency the ingress can be created before the
            // controller's Helm release, so waitForLoadBalancer never sees a hostname and
            // status stays null, breaking the Route53 alias record.
            .dependsOn(List.of(lbRelease))
            .metadata(ingressMetadata)
            .spec(alb)
            .lifecycle(TerraformResourceLifecycle
                           .builder()
                           .preventDestroy(false)
                           .build())
            .waitForLoadBalancer(true)
            .build();
    }

    public Release getLbRelease() {
        return lbRelease;
    }

    @Override
    public List<ITerraformDependable> getServiceDependables() {
        // The Keycloak Service is NodePort/LB-annotated, so its creation triggers the
        // aws-load-balancer-controller's mservice mutating webhook (failurePolicy=Fail). Wait on
        // the controller's Helm release (atomic, so Helm blocks until pods are Ready) to avoid the
        // "no endpoints available for service aws-load-balancer-webhook-service" race.
        return lbRelease == null ? List.of() : List.of(lbRelease);
    }

    public void configureLoadBalancerDestroy(List<Service> loadBalancers) {
        Resource.Builder
            .create(this, "cleanupELBs")
            // Ensure this runs only after the K8s Services are destroyed by Terraform
            .dependsOn(loadBalancers)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();
        Resource.Builder
            .create(this, "cleanupSecurityGroups")
            .dependsOn(loadBalancers)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();
    }


    public List<String> getSubnetIds() {
        return new ArrayList<>(subnetIds);
    }

    @Override
    public List<ITerraformDependable> getDependables() {
        return eksDependOn; // List.of(cluster, eksAuthData);
    }

    @Override
    public String getBase64CertData() {
        return cluster.getCertificateAuthority().get(0).getData();
    }

    @Override
    public String getClusterEndpoint() {
        return cluster.getEndpoint();
    }

    @Override
    public String getToken() {
        return eksAuthData.getToken();
    }

    public String getPolarisDomainName() {
        return polarisDomainName;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getRegion() {
        return region;
    }
}
