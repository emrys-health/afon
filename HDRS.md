# HDRS fork notes

This is the HDRS-patched clone of Emrys's `afon-core`, deployed for the
SDE-node POC onto the cluster built by `hdrsuk/infra-aws-poc` - **not** the
cluster afon would create for itself. Patch plan and rationale:
`infra-aws-poc/docs/afon-integration.md`.

This repo's `main` is a single snapshot import of upstream **`56cfb474`**
(2026-08-28), replacing the earlier `b1131be2` import which had fallen 267
commits behind. Full upstream history stays with Emrys - it cannot be mirrored
here, because push protection blocks the credentials it contains.

Patches are one commit per item, prefixed `Patch N:`, so the diff against
upstream stays reviewable.

## The 2026-08-28 re-import

The old note in this file said, of the `b1131be2` patch set: *do not attempt to
rebase these patches onto current upstream; take a fresh import and re-apply
only the patches still needed.* That is what happened. Upstream had moved 267
commits, and every file we patched had moved with it.

**Three of the sixteen items were dropped, because upstream fixed them:**

| Old patch | Why it is gone |
|---|---|
| 9 - `StepOperations` cannot be constructed AWS-only | `[#130]` made it hold an `AfonPropertyProvider` and read lazily at the use site. The eager per-cloud constructor fields the patch worked around no longer exist. |
| 10 - `-c aws` never reaches the forked JVM | `[#94]` replaced the `jvm-args.txt` files with `${afon.jvm}` and added `${afon.exec}`, which forwards `-Dafon.cloud` and `-Dafon.home` into the fork explicitly. |
| *Restore the hub's OIDC login* | Never upstream's bug. Our own Patch 15 deleted the `generic-oauth` block as a side effect of rewriting the text block beside it, and that commit put it back. Upstream now carries the whole block natively, so the fresh application simply does not touch it. |

**Two new patches exist that had no counterpart before**, because upstream
grew new behaviour that our patches collide with - see 17 and 18 below.

**Upstream API changes absorbed throughout:** `AfonProperties` (a class of
static fields) became the `AfonProperty` **enum**; `X.getValueFrom(props)`
became `props.getValue(X)`; `Properties` became `AfonPropertyProvider`;
`AwsPolarisStack` now extends `AbstractCloudStack`; `ConfigUtils` centralises
`afonHome`/`afonCkdtf`/`stackDir`/`tfStatePath`; `PolarisConfigClient` became
`PolarisCatalogClient`; Helm charts are vendored into the repo (`[#157]`).

## Do NOT run these upstream bootstrap scripts

The AWS resources they create imperatively all come from `infra-aws-poc`
Terraform instead:

| Upstream script | Replaced by |
|---|---|
| `scripts/aws/create.sh` (buckets, S3ReadWriteRole, whitelist + LBC policies) | `infra-aws-poc` `storage.tf` + `iam.tf` |
| `scripts/aws/create_federated_permissions.sh` (GlueIcebergRole) | nothing - Glue catalog is patched out (AWS-only) |
| `scripts/aws/user/` (IAM user + keys) | `hdrs-poc-afon-svc` user in `iam.tf`; keys created out of band |
| `scripts/aws/destroy.sh` | `terraform destroy` in `infra-aws-poc` (afon stack first) |

Note the deliberate quirk: `infra-aws-poc` creates the load-balancer-controller
policy under upstream's expected-but-misspelled name
`AWSLoadBalancerControllerIAMPolicyCurl`, so this code needs no patch for it.

## Building and deploying

Two things changed at the 56cfb474 import and the deploy scripts were updated
to match:

- **`-pl '!afon-distribution'` no longer works.** That module moved into a
  Maven profile, so it is not in the default reactor and excluding it fails
  with "Could not find the selected project in the reactor". Drop the flag.
- **`-Dexec.skip=true` is now required on `install`.** Upstream wired document
  generation into the build, and `GenerateAllDocumentsMain` walks every
  document including `DeployAwsFullStackWithAzurePolarisMain`, which demands
  `AZURE_DOMAIN_NAME`. On an AWS-only config the build dies there. The
  generators are manual helpers, not part of the deploy, so skipping the exec
  goal is right - and it is a build flag rather than fork divergence.

```sh
mvn -q -DskipTests -Dexec.skip=true -P -acceptance install
```

## Configuration

`~/.afon/config` values come from `terraform output afon_aws_properties` in
`infra-aws-poc` (keys already named as afon expects), plus secrets gathered
per that repo's README (RDS password from Secrets Manager, svc-user access
key, Keycloak bootstrap admin, oauth2-proxy client/cookie secrets, `users.csv`).
Config is local only - never committed anywhere.

**New required key at this import: `ALLOWED_DOMAINS_CSV`.** Synth fails
outright without it. See Patch 17.

## Patch status

| Item | Status |
|---|---|
| 1 - consume pre-existing cluster via data sources | Done. Config keys `AWS_VPC_ID`, `AWS_PRIVATE_SUBNET_IDS`. Upstream still builds its own EKS, so this is as necessary as ever. Note upstream had grown a **second** LBC role creation site further down the constructor; that goes too. |
| 2 - tolerate missing vpc-cni/kube-proxy addons | **No change needed** - upstream still `\|\| echo`-guards every delete |
| 3 - account ids | Done |
| 4 - AWS-only Polaris catalogs | Done, and **simpler than before**: upstream consolidated the two duplicated `createAllCatalogs` copies into the `PolarisCatalogClient` constructor, so this is one site instead of two. `AwsForbiddenPolarisCatalog` is new upstream work and is left out - it needs `AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS`, a bucket `infra-aws-poc` does not create |
| 5 - skip bootstrap scripts | Done (documented above; nothing to run) |
| 6 - EKS version/sizing constants | Moot - creation code removed by item 1 |
| 7 - all load balancers internal | Done |
| 8 - GCP credentials optional (AWS-only synth) | Done |
| 9 - `StepOperations` constructible AWS-only | **DROPPED - fixed upstream by `[#130]`** |
| 10 - forward `afon.cloud`/`afon.home` to the forked JVM | **DROPPED - fixed upstream by `[#94]`** |
| 11 - shell-quote exported secrets | Done - upstream still has no quoting |
| 12 - configurable Polaris datasource username | Done - still hardcoded `postgres` upstream |
| 13 - S3 remote state with native locking | Done, and moved: `[#160]` centralised an explicit `LocalBackend` in `AbstractCloudStack`, so one constructor now covers every cloud stack instead of just `AwsPolarisStack` |
| 14 - audit perimeter sidecar (svc-audit-log step 2, HTTP_PROXY) | Done. Keys `AUDIT_SIDECAR_IMAGE` (the gate), `AUDIT_KAFKA_BOOTSTRAP`, `AUDIT_SCHEMA_REGISTRY_URL`, `AUDIT_AE_ID`, `AUDIT_PROJECT_ID`, `AUDIT_TRE_ID` (defaults to `hdrs-afon`). The sidecar also gets `AE_SESSION_ID` from the Downward API - no config key, see below |
| 15 - the redirect init container (step 3, transparent) | Done. Keys `AUDIT_REDIRECT_IMAGE` (the gate between steps 2 and 3), `AUDIT_TRANSPARENT_PORT` (15001), `AUDIT_EXCLUDED_PORTS` (`8081 15002`) |
| 16 - sidecar image refs from the `perimeter-images` ConfigMap | Done |
| **17 - keep the audit perimeter reachable under upstream's egress policy** | **NEW - see below** |
| **18 - stop two Services forcing replacement** | **NEW - see below** |
| **19 - deterministic rendering (two sites)** | **NEW - see below** |
| **20 - Tetragon, the audit platform's exec sensor** | **NEW - see below.** Keys `AUDIT_EXEC_SENSOR_ENABLED` (defaults to **true** - see Patch 24), `AUDIT_EXEC_SENSOR_EXPORT_DIR` (defaults to `/var/run/cilium/tetragon`) |
| **21 - the four `hdrs.audit/*` pod labels** | **NEW - see below.** No new config keys: three reuse `AUDIT_AE_ID` / `AUDIT_PROJECT_ID` / `AUDIT_TRE_ID`, and `hdrs.audit/user-id` is the OIDC `sub` from `auth_state` |
| **25 - the cell intake, and the sensor for the half nothing observed** | **NEW - see below.** One new key `AUDIT_CELL_INTAKE_PORT`, defaulting to `15003` - no config change needed. `USER_ID` reaches the sidecar from the `hdrs.audit/user-id` label by the Downward API - no key. The sensor is copied into the workspace by an init container; **the notebook image is untouched** |
| **24 - the exec sensor's scope is derived, not configured** | **NEW - see below.** Replaces `AUDIT_EXEC_SENSOR_SELECTOR` with `AUDIT_EXEC_SENSOR_ENABLED` (default `true`) |
| *(unnumbered)* image-pull race and VNC password | Done - upstream still has `image_pull_policy = 'Always'`, `start_timeout = 300` and `VNC_SESSIONS` |

### Patch 17 - the audit perimeter vs upstream's new egress lockdown

Upstream `[#83]`/`[#118]`/`[#147]` added `NetworkConfig`, which applies an
egress NetworkPolicy to **every notebook pod**: DNS, the JupyterHub hub, Spark
Connect on 15002, and a Cilium FQDN allowlist read from `ALLOWED_DOMAINS_CSV`.
Everything else is dropped.

The audit perimeter sidecar from Patches 14-16 runs **inside** the notebook
pod, so it shares that policy - and its egress to Kafka and the schema registry
is dropped with it. **Nothing reports this.** The workspace keeps working, the
sensor keeps running, and audit events simply stop arriving. On a health-data
platform that is the worst available failure mode: the control appears present
and is not.

Patch 17 adds an egress rule to the audit platform's namespace on 9092 and
8081, selected by the namespace label `app.kubernetes.io/part-of=audit-platform`
rather than by name. Gated on `AUDIT_KAFKA_BOOTSTRAP`, exactly as the sidecar
is gated.

**`ALLOWED_DOMAINS_CSV` is a policy decision, not a config chore.** It is set
to the platform's own hostnames only. `pypi.org` and `files.pythonhosted.org`
are deliberately **not** whitelisted: package installs from a workspace are an
egress route out of the SDE, and that is a decision to take explicitly rather
than inherit from an upstream bump. Whoever wants them adds them knowingly.

### Patch 18 - and the retirement of the "do not run a full apply" warning

This file carried a standing warning from 2026-08-21 that a full `tofu apply`
must never be run, because the plan wanted to **replace**
`kubernetes_service.polaris-internal` and `kubernetes_service.spark-master-lb`,
destroying and recreating the live load balancers. Every change since had to be
applied surgically with `-target`.

The cause, read from the plan's own `replace_paths` rather than guessed:

| Service | Field | Live | Declared |
|---|---|---|---|
| `polaris-internal` | `spec[0].cluster_ips` | `["172.20.116.194"]` | `[]` |
| `spark-master-lb` | `spec[0].load_balancer_class` | `"service.k8s.aws/nlb"` | `null` |

Both are assigned by the cluster, not chosen by afon - the ClusterIP by
Kubernetes, `load_balancer_class` by the AWS Load Balancer Controller.
Declaring them empty reads as a change against any live Service and forces
replacement, which means this would recur on **every** cluster the moment afon
had been applied to it once.

Patch 18 ignores those two fields. The plan went from
`3 to add, 16 to change, 2 to destroy` to
`1 to add, 16 to change, 0 to destroy`.

**The warning is therefore retired.** Config-only changes no longer need the
`-target` dance. Worth sending upstream - it affects anyone who applies afon
twice.

### Patch 19 - `Map.of` and `Set.of` are randomised, and it showed

Found by re-planning straight after the deploy: a plan with **no input
changes** still wanted to replace `null_resource.domainWhitelistPolicy` and
update the Keycloak Deployment.

Java randomises the iteration order of `Map.of`/`Set.of` per JVM run, by
design. Upstream renders both straight into Terraform config, so both came out
differently on every synth:

| Site | Rendered as | Cost of the churn |
|---|---|---|
| `NetworkConfig.toJson` | the `triggers` value on `domainWhitelistPolicy` | the CiliumNetworkPolicy was **deleted and re-applied on every apply** - a window with no FQDN egress allowlist, on a cluster whose whole point is restricting egress |
| `KeyCloakConfig` env list | the container's `env` array | the Keycloak pod **restarted on every apply** - an authentication blip, for nothing |

Neither is visible in the plan as a problem: the policy is present before and
after, and the Keycloak diff shows only names sliding between positions
(`~ name = "KC_DB_URL" -> "KC_DB_USERNAME"`), which reads like noise.

Confirmed rather than assumed - two consecutive synths of identical input gave
the same length and different digests (`6fbcce81cc3ddf24`, `8c95357455a851ad`),
and after the fix the same digest twice (`48324606317ef6fd`).

**With 18 and 19 together, `tofu plan` now reports `No changes` against the
live stack.** This deployment is idempotent for the first time.

**Briefly superseded, and restored by Patch 23.** The pipeline's triple-synth
check found three further `Map.of`/`Set.of` sites Patch 19 did not cover, one
of them tracked by Terraform, so `helm_release.trino` showed a change on every
plan. Patch 23 ordered all three and the idempotency result holds again - see
"Three more `Map.of` sites" under **The HDRS pipeline** below.

### Patch 20 - Tetragon, and why the audit platform's sensor lives in afon

The audit platform collects *what a researcher ran* from an eBPF exec sensor on
the node, consumed by a node agent of its own
(`hdrsuk/svc-audit-log`, ADR 0031). The sensor is Tetragon, and this patch
installs it as a Helm release beside Cilium.

**Why here and not in `infra-aws-poc`.** `infra-aws-poc` is HDRS's own cluster
and is never handed to anyone. afon is what a TRE is given to run. So a
requirement the platform places on a TRE belongs in afon, where every TRE gets
it, rather than in the one cluster repository that only HDRS will ever apply.
Putting it in `infra-aws-poc` would have made the collection work on hdrs-poc
and nowhere else, leaving every third-party TRE to reinvent it.

This does not make afon trusted to report on itself. Tetragon is the *sensor*;
the platform's node agent, the thing that turns its events into audit records,
runs outside afon entirely. And a TRE that declines to install this is not
silently unaudited: the platform's `manifest_conformance` rule raises
`ESCALATE` when a declared observation tier never reports.

**The scope is not configurable, and the gate is on by default.** Tetragon
reports **every** process on the node, and the scoping is the only thing
standing between the audit platform and the exec stream of JupyterHub,
Guacamole, Polaris, Spark and Trino - namespace alone would not do it, since
afon runs in `default` alongside all of them. That selector is therefore
derived from `JupyterHubConfig#singleuserSelector()`, the same constant the
pods are stamped from, rather than configured. See Patch 24, which corrected
this: it shipped as a config key first.

`AUDIT_EXEC_SENSOR_ENABLED` defaults to `true`, so no configuration is needed
to get the sensor - see Patch 24 for why that default is the decision rather
than a convenience.

**Two chart defaults are deliberately overridden**, and both would have been
wrong quietly rather than loudly:

- `export.mode` defaults to `stdout`, which adds a sidecar tailing the export
  to the container's stdout. promtail ships every container's stdout to Loki,
  so the default would have copied every researcher's exec events into Loki as
  a side effect - a store with different retention and different access control
  from the audit store. Set to `""`, which the chart's own comment documents as
  the way to disable it. The file itself is written either way:
  `tetragon_configmap.yaml` sets `export-filename` whenever
  `tetragon.exportFilename` is non-empty, and `daemonset.yaml`'s `export-logs`
  hostPath is unconditional.
- `tetragon.exportAllowList` defaults to six event types. The node agent parses
  only `process_exec`, so the allowlist names `PROCESS_EXEC` alone. An event
  nobody reads is a file on a node growing for no reason.

**Note the two levels.** `exportDirectory` and `export.mode` are top-level chart
values; `exportFilename`, `exportAllowList`, `exportDenyList` and the rotation
settings live under `tetragon:`. Writing the second group at the top level is
silently ignored - the release installs, Tetragon runs, and the default
six-event allowlist exports **every pod on the node**. `exportDirectory`'s
default is already `/var/run/cilium/tetragon`, which is exactly what the audit
platform's node agent mounts, so the two sides agree out of the box; it is set
explicitly anyway because it is a cross-repository contract.

**The chart is vendored**, like Cilium's and the other eight - see
`CdkUtils#vendoredChart`. `afon-cdk/src/main/resources/charts/tetragon-1.7.1.tgz`,
sha256 `79ec0f7d35b6fcad99ca317348deac136c00b674430c28be17aa818d2015c632`,
matching the digest published for 1.7.1 in `https://helm.cilium.io/index.yaml`.
The images are **not** vendored and are not mirrored into ECR: the chart pulls
`quay.io/cilium/tetragon:v1.7.1` directly, exactly as the Cilium release already
pulls `quay.io/cilium/cilium`.

### Patch 21 - the four labels, and why the sidecar's env vars will not do

Patch 20 installs the exec sensor. This is what makes its events attributable.

The node agent runs on the Kubernetes node. It never sees a workspace pod's environment, so
the `AUDIT_AE_ID` / `AUDIT_PROJECT_ID` / `AUDIT_TRE_ID` that Patch 14 hands the perimeter
sidecar are invisible to it - one container cannot read another's environment. What Tetragon
does put on every exec event is `pod_labels`, so labels are the whole route by which the
identity of a workspace reaches a sensor outside it.

The agent reads exactly four, and **a pod missing any of them produces nothing** - by design
and by test in svc-audit-log. Before this patch our workspace pods carried none, so the
sensor from Patch 20 would have installed, run, beaten a healthy heartbeat and reported
silence.

| label | source |
|---|---|
| `hdrs.audit/ae-id` | `AUDIT_AE_ID`, synth-time |
| `hdrs.audit/project-id` | `AUDIT_PROJECT_ID`, synth-time |
| `hdrs.audit/tre-id` | `AUDIT_TRE_ID`, synth-time (`hdrs-afon`) |
| `hdrs.audit/user-id` | the OIDC `sub`, per-spawn, from `auth_state` |

No new configuration keys. The first three are values afon already has; only `user-id` needed
new code, because the OIDC claims exist solely inside the `auth_state` hook.

**`sub`, and deliberately not the e-mail.** The `sub` is opaque - meaningless without the
identity provider - and that opacity is exactly what makes putting it in cluster metadata,
readable by anyone with `get pods`, an acceptable disclosure. `hub.jupyter.org/username`
already carries the e-mail and is *not* a substitute: it identifies the person to anyone who
reads it. It is also not `spawner.user.name`, even though `username_claim` is `email` here
and the two would agree - partners run afon against their own IdPs, and an audit trail must
key on the claim the platform specified rather than on whatever afon uses as a display name.

**Refusing beats mangling.** Kubernetes label values are at most 63 characters, must begin
and end alphanumeric, and allow only `-`, `_` and `.` between. A Keycloak `sub` is a UUID and
fits; an ADFS-style `abc|def` does not. A truncated or rewritten `sub` is a *different*
identifier, and an audit trail keyed on a silently altered one is worse than an absent label
- so an unusable value is refused and logged as a warning naming the consequence, rather than
coerced. If a partner IdP ever produces a `sub` that cannot be a label value, the fix is for
the agent to read a pod *annotation*, which has no such constraints; that is a change in
svc-audit-log, not a reason to mangle the claim here.

The ASCII check is written out rather than using `str.isalnum()`, which is Unicode-aware:
`'é'.isalnum()` is `True` in Python and invalid in a label, so `isalnum()` would pass values
the API server then rejects - turning a bad label into a pod that will not spawn at all.

**One bug fixed in passing.** `_on_spawn` copied `spawner.extra_labels` only when the user had
a Guacamole slug. `c.KubeSpawner.extra_labels` is a single dict shared by every spawner, so
any write outside that branch mutates shared state and leaks one user's label into the next
user's pod - the same hazard the environment copy below it already guards against. The copy
is now unconditional and up front.

### Patch 24 - the exec sensor's scope is derived, not configured

Patch 20 shipped `AUDIT_EXEC_SENSOR_SELECTOR`, a config key holding
`afon-role=jupyter-singleuser`, on the reasoning that making the gate and the
scope one value meant the sensor could never be installed unscoped. The safety
property was real; the price was wrong.

**That string is not a deployment fact.** `JupyterHubConfig` defines it
(`SINGLEUSER_LABEL_KEY` / `SINGLEUSER_LABEL_VALUE`) and stamps it on every pod
it spawns. The config key retyped a constant this repository already owns, as a
hand-edited string in a properties file no compiler checks - so a change to the
constant, or a typo, would leave the sensor scoped to a label nothing carries.
It would install, run, and export nothing, which is the failure this fork keeps
having to write comments about.

It is worse than a duplicated literal, because this selector is **load-bearing
three times**: the egress `NetworkPolicy` selects on it, the pods carry it, and
now the exec sensor's export allowlist matches it. It has been wrong once
already - `deployJupyterHub` returned the proxy's labels rather than the
notebook's, and a workspace everyone believed was default-denied was restricted
by nothing. Adding a third consumer that reads the value from somewhere else
makes that same failure available again.

So the selector now comes from `JupyterHubConfig#singleuserSelector()`, added
for this, and the key is gone. The mis-scoping bug is no longer expressible.

**`AUDIT_EXEC_SENSOR_ENABLED` defaults to `true`**, which makes it the only
`AUDIT_*` key that is on unless switched off. That is deliberate and it is a
decision about what this fork is: a TRE adopting HDRS's afon is adopting the
audit platform with it, so collecting what a researcher ran is part of what
afon *is* here rather than a feature to remember to enable. The perimeter
sidecar's gates are shaped the other way round only because they need an image
reference nobody can guess; this needs nothing, so it needs no excuse to be on.

`AUDIT_EXEC_SENSOR_ENABLED=false` is a real escape hatch, for a cluster owner
who will not accept a privileged cluster-wide DaemonSet - a deliberate act with
a name, rather than the result of not knowing a key existed.

### Patch 25 - the cell intake, and the sensor for the half nothing observed

Patches 20, 21 and 24 make a researcher **running a file** visible. This makes a researcher
**running a cell** visible, and nothing ever has.

**Cells are structurally invisible to the exec sensor, and no work on the node changes that.**
Opening a notebook execs `ipykernel_launcher` exactly once; every cell after it travels over
ZMQ to a kernel that is already running and never calls `exec()` again. eBPF exec tracing
cannot see cells by construction. So `NOTEBOOK_CELL` - half of svc-audit-log's `UserInputLog`,
with a schema, a topic, a rule and tests since the work began - had no producer anywhere.

**Three things change, and the notebook image is not one of them.**

| where | what |
|---|---|
| `AfonProperty` | `AUDIT_CELL_INTAKE_PORT`, defaulting to `15003` |
| `JupyterHubConfig` | `CELL_INTAKE_PORT` and `USER_ID` on the sidecar; an init container that installs the sensor; an `emptyDir` the notebook mounts; `PYTHONPATH`, `JUPYTER_CONFIG_PATH` and `HDRS_AUDIT_SIDECAR_PORT` in the notebook |

**The events go to the perimeter sidecar, not to the node agent.** svc-audit-log's ADR 0031
originally leaned the other way and was amended: the node agent has no ingress of any kind,
adding one to a root `hostPID` `CAP_SYS_PTRACE` DaemonSet is real attack surface, and it would
have needed an egress rule out of the notebook container. The sidecar is already in the pod,
already holds the identity, and shares the pod's network namespace - so the extension reaches
it on `127.0.0.1`, and **this patch asks for no NetworkPolicy change at all**. Patch 17's
existing rule to the audit namespace already covers what the sidecar sends onward.

**No second container, and no second heartbeat.** The intake is a third way into the sidecar
that is already there, beside the CONNECT proxy and the transparent redirect - one container,
one sensor id, one heartbeat. A sensor of its own would have kept `sensor_id` mapping onto the
observation tier, and it was judged not worth a second thing in the pod that has to stay alive.

**`USER_ID` needs no new key**, which is worth stating because it looks like it should.
`UserInputLog` requires an actor and the sidecar has never carried one - a `NetworkFlow` has
no `user_id`. It comes from the `hdrs.audit/user-id` label Patch 21 already stamps, by the same
Downward API route `AE_SESSION_ID` uses: `metadata.labels['<KEY>']` is available to a container
env `fieldRef`, not only to a `downwardAPI` volume. That is also **less** disclosing than the
label, which anyone with `get pods` can read. And because the kubelet resolves it when it
creates the pod, it does not depend on the order the `auth_state` and `pre_spawn` hooks run in.

**A researcher cannot attribute a cell to anybody else, and that is why identity is stamped
here.** The extension runs in the researcher's own container, so anything it asserted about
*who* ran a cell is something a researcher could assert about anyone. The posted body carries
code and kernel only. A cell that never ran can be posted - they control the kernel, so that
was always true - and a cell against another researcher's name cannot.

#### The sensor is mounted in, and the notebook image is left alone

This was going to be a `pip install` in `scripts/docker/jupyter/Dockerfile`. Reading that image
made it the wrong answer three times over, and the reasons are worth keeping written down:

- **It is published by hand to a third party's registry.** Every sensor change would need
  somebody else's release.
- **`image_pull_policy` is `IfNotPresent`** - set deliberately, because re-pulling a ~3 GB image
  on every spawn pushed nodes past their eviction threshold and killed pods mid-download
  (observed 2026-08-24). So a rebuilt image **under the same tag is not picked up at all**: each
  sensor change would need a tag bump and a `SINGLEUSER_IMAGE` change beside it.
- **A baked sensor cannot roll through the `perimeter-images` ConfigMap**, which is how a new
  sidecar already reaches the next spawned workspace with no rebuild and nobody logged out.

So an init container from the **perimeter image** copies the package and its config into an
`emptyDir` the notebook mounts at `/opt/hdrs-audit`. The sensor rides `AUDIT_SIDECAR_IMAGE`
exactly as the sidecar does, and this patch touches no image at all.

A copy is sufficient because **the package depends on nothing**: it imports `jupyter_server`,
which the notebook already has, and otherwise only the standard library. There is no package
index reachable from inside that container, so this is the property the arrangement rests on,
and svc-audit-log has a test that fails if a third-party import appears.

The init container runs as **root with every capability dropped** - only to write into a fresh
`emptyDir`, whose ownership depends on how the kubelet creates it. The perimeter image otherwise
runs as uid 1337, and the failure mode of guessing wrong is a workspace that will not spawn. The
installer `chmod`s what it writes to 0644/0755 so the notebook's own uid can read it.

The notebook needs **both** `PYTHONPATH` (so the module imports) and `JUPYTER_CONFIG_PATH` (so
`jpserver_extensions` enables it). Neither alone does anything. Both are *prepended* to whatever
is already set - both are unset in this image today, but a future image that set either would
otherwise lose it silently, and `PYTHONPATH` is the sort of thing a Spark image acquires.

#### It is removable, and that is handled one level down

A researcher can uninstall the extension or start their own lab without it - no root, no
`ptrace` - so it is given no heartbeat, because a check-in from something removable attests
nothing. Patch 20's exec sensor sees `jupyter lab` start with full argv, including the flags of
a lab started with extensions disabled, so a lab running with no cells arriving is reportable.
**The sidecar's heartbeat does not cover this** and must not be read as covering it: it says the
receiver is alive, and goes on saying so after the extension has been removed.

#### The port is defaulted, and there is no config change to make

`AUDIT_CELL_INTAKE_PORT` was empty by default, as a gate against a sidecar listening for cells
no extension would send. **That premise is gone**: the sensor is copied into the workspace out
of the sidecar's own image, so wherever there is a sidecar there is a sensor. `15003` is the
default now, exactly as `AUDIT_TRANSPARENT_PORT` defaults to `15001` - a port two containers
have to agree on is configuration of the arrangement, not a version that rolls.

`AUDIT_SIDECAR_IMAGE` remains the real gate. Unset, there is no sidecar, no intake, no init
container and no extension: an afon deployment with no external auditor is untouched by any of
this.

There is also a practical reason, and it is worth being honest about. The config bundle lives
in Secrets Manager and is pushed from a laptop by `scripts/hdrs/ci-config.sh push` - it cannot
be changed from CI, and it must not move into GitHub secrets, because the same bundle carries
the RDS master password and the svc user's access key. A port that only exists in that bundle
is a port nobody can set from a pull request. A port is not a secret, so it belongs in code.

The loopback bind is what makes a fixed default safe: it collides with nothing outside the pod,
and `services/perimeter-redirect/redirect.sh` returns loopback traffic untouched
(`iptables -t nat -A "$CHAIN" -o lo -j RETURN`), so a cell never crosses the proxy on its way to
the sidecar and needs no entry in `AUDIT_EXCLUDED_PORTS`.

#### Rolling it out

Apply, restart the hub (`jupyterhub_config.py` is read at hub **startup**, so a ConfigMap update
alone does nothing), and workspaces pick it up as they respawn.

A new sensor version afterwards is just a new `AUDIT_SIDECAR_IMAGE` in the `perimeter-images`
ConfigMap, which svc-audit-log's Deploy workflow patches. No image build here, ever.

### The session identifier, and why it is not a config key

The perimeter sidecar now also receives `AE_SESSION_ID`, taken from the pod's
own `metadata.uid` through the Downward API:

```python
{'name': 'AE_SESSION_ID',
 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.uid'}}},
```

`AUDIT_AE_ID` names the analytical environment and is stable for one researcher
across every login. It cannot also name the stretch of time a sensor can speak
for, and until now it was being asked to: the platform's negative observations
("nothing left this environment in that window") were qualified by an id whose
life is far longer than any one perimeter's, so a window could silently span
hours in which nothing was watching. `AE_SESSION_ID` is the second identifier,
per spawn. The reasoning, and the options rejected on the way, are in
svc-audit-log's ADR 0028.

**`metadata.uid` and not `metadata.name`.** KubeSpawner derives the pod name
from the username, so it is the same string on every spawn - it would identify
the researcher rather than the login, which is both the wrong value and a
personal slug on every audit event. The uid is minted per pod, which is exactly
what a session is.

**No `AfonProperty` for it**, because there is no value to configure: only
Kubernetes knows a pod's uid, and it does not exist until the pod does. That is
also why this is `valueFrom` rather than `value`, and therefore why it consumes
no `%N$s` slot in the `.formatted(...)` list - the numbering after `AE_ID` is
unchanged.

**`AUDIT_TRE_ID` now defaults to `hdrs-afon`, not `hdrs`.** `hdrs` names the
Organization; `tre_id` names one deployment of a TRE framework, and Emrys runs
another from the same framework. Nothing parses the string - the framework is
readable in it for a human, not for code. The rename matters because an audit
trail cannot be re-keyed: events already written under an ambiguous identifier
stay permanently unattributable. The consumer side already expects the new
value. Anything setting `AUDIT_TRE_ID` explicitly in `~/.afon/config` overrides
this and should be updated to match.

## ⚠️ The RDS master password rotates, and the config does not follow

This cost a three-day Polaris outage that nobody had diagnosed, and it will
recur.

The Polaris and Keycloak databases live on RDS whose master password is managed
by AWS in the secret `rds!db-87085e16-1021-4bdf-895c-d6938582e3fa` - the
`rds!db-` prefix means AWS owns it and **rotates it automatically**.
`~/.afon/config/secret.properties` holds a static copy. When AWS rotated it,
the copy went stale and nothing noticed.

The symptom is deceptive. Polaris kept reporting

```
Database connections health check DOWN:
  FATAL: password authentication failed for user "afon_admin"
```

while sitting in `0/1 Running` **with zero restarts** for three days: the
process held its existing connection pool, so it never crashed, and a pod that
is `Running` does not attract attention. The failure only became loud when this
deploy restarted the pod and it could no longer open any connection at all -
`Error`, then `CrashLoopBackOff`.

Refresh it with the fingerprint check rather than by eye - the values are 28
characters of noise and comparing them visually is how you convince yourself
they match when they do not:

```sh
# config
grep -m1 '^POLARIS_DB_PASSWORD=' ~/.afon/config/secret.properties | cut -d= -f2- \
  | tr -d '\n' | sha256sum | cut -c1-12
# what RDS actually has
aws secretsmanager get-secret-value --profile hdrs-management --region eu-west-2 \
  --secret-id 'rds!db-87085e16-1021-4bdf-895c-d6938582e3fa' \
  --query SecretString --output text \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['password'],end='')" \
  | sha256sum | cut -c1-12
```

Then re-synth and apply; the Polaris pod needs deleting by hand afterwards,
because a mounted-secret change does not restart it.

**The real fix is not to hold a copy at all.** This is exactly what External
Secrets Operator is for, and it is already the org's chosen direction (open
decision 3: cloud secret manager now, Vault/OpenBao later). Until then, check
the fingerprint whenever Polaris or Keycloak misbehaves - and note that
"`Running` with 0 restarts" is not evidence of health.

## ⚠️ State afon does NOT create (a fresh environment needs all four)

afon has only ever been deployed onto Emrys's own estate, where this state
already exists from earlier runs. On a genuinely fresh environment the deploy
**cannot get past the catalogue step** without all four, and the errors are
misleading (504, then 500, then 401 - none of which say "missing state").

| # | Missing | Symptom | Fix |
|---|---|---|---|
| 1 | `polaris_db_aws` database | Polaris cannot start | `CREATE DATABASE polaris_db_aws;` |
| 2 | Polaris metastore bootstrap (schema + `root` principal) | 401 on every call; database has **zero tables** | `kubectl apply -f scripts/afon/polaris-bootstrap.yaml` (in infra-aws-poc) |
| 3 | Keycloak `polaris` realm | `OIDC server is not available at .../realms/polaris`, and oauth2-proxy hangs in `Init:0/1` forever | `scripts/afon/create-realm.sh` |
| 4 | Keycloak client for the Polaris root identity | 401 `invalid_client` | `scripts/afon/create-polaris-client.sh` |

Ordering note for #3: afon creates the realm in `polaris_spark_talks_to_aws`,
which runs **after** `all_catalogs_in_polaris` - but the catalogue step needs
the realm to authenticate. Its own step order cannot succeed on a first run.

## ⚠️ Identity names are not free text

`POLARIS_ID` and `USER_POLARIS_ID` must equal **real Polaris principal names**.

Polaris is configured (`KeyCloakHelper.polarisOidcEnv`) with
`polaris.oidc.principal-mapper.name-claim-path=polaris_principal`, and
`KeyCloakProvider.createConfidentialClient` stamps that claim with the
**clientId**. So three things must be the same string: the Keycloak clientId,
the Polaris principal, and the config value.

- `POLARIS_ID=root` - created by the metastore bootstrap (#2 above)
- `USER_POLARIS_ID=testuser` - a row in `users.csv`; its secret is that user's password

Inventing plausible-looking values here yields a 401 that reads as a wrong
password. Polaris's own token endpoint returns **501** because authentication
is external, so all tokens come from Keycloak.

**Observed 2026-08-28, and worth knowing:** the live Trino catalogs were
configured with `afon-polaris-user:<secret>`, a principal that **does not
exist** - the `polaris` realm holds `root`, `testuser`, `afon-jupyter` and the
per-user clients, and nothing else. The 56cfb474 deploy corrects Trino to
`testuser`, which does exist. That is a fix, not a regression, but it means the
live Trino catalogs had been failing authentication before it.

## The HDRS pipeline (`.github/workflows/tofu.yml`)

Added 2026-09-04. Plan on every pull request, apply only by hand - the same
shape as `hdrsuk/svc-audit-log` and `hdrsuk/svc-governance-registry`.

**It does not replace `aws-stack-linux.yml`,** which stays exactly as it is.
The two answer different questions and confusing them is expensive:

| | `aws-stack-linux.yml` (upstream's) | `tofu.yml` (this) |
|---|---|---|
| Entry point | `ops.DeployAwsFullStackMain` | synth, then `tofu plan`/`apply` |
| First thing it does | **`deleteStack`** - tears the stack down | nothing destructive |
| Cost of a run | ~1 hour with Polaris and Jupyter down | a normal apply |
| Right for | a fresh environment | a change |
| Runner | Emrys's self-hosted | GitHub-hosted, over the Client VPN |

It only became possible at Patches 18 and 19. Before those, a full apply wanted
to replace two live load balancers and re-render the Cilium policy every time,
so every change went through the `-target` dance and a pipeline would have had
nothing safe to run.

**Why a plan on a pull request is worth more here than in a normal repo.** This
stack's Terraform is generated, not written, so a `.java` change *is* an
infrastructure change and review of the Java cannot show you its effect.
Patches 18 and 19 were both found by planning and reading the output - two load
balancers about to be destroyed, and a network policy deleted and re-applied on
every apply. Neither was visible any other way.

### What it needs

| Thing | Where | Set by |
|---|---|---|
| `AWS_PLAN_ROLE_ARN` | repo variable | `terraform output afon_core_plan_role_arn` in `infra-aws-poc` |
| `AWS_DEPLOY_ROLE_ARN` | repo variable | `terraform output afon_core_deploy_role_arn` |
| `VPN_OVPN_PROFILE` | repo secret | the `ci-deploy` profile, reused - see below |
| `hdrs-poc/afon-core-ci-config` | Secrets Manager | `./scripts/hdrs/ci-config.sh push` |

**The VPN profile is shared with `svc-audit-log`, not minted per repo.** The
PKI convention looked per-repo (`ci-deploy.ovpn`, `ci-deploy-registry.ovpn`),
and a third would have kept one revocation to one pipeline; reuse was chosen
deliberately on 2026-09-04 to avoid another client certificate on an
environment due to die on 2026-10-31. The consequence to remember: revoking
`ci-deploy` now stops **two** pipelines, and a leak from either repository is a
network credential for both. Issue `ci-afon-core` with
`infra-aws-poc/scripts/vpn-certs.sh issue` if that trade stops being worth it.

The two roles and the secret container are declared in
`hdrsuk/infra-aws-poc/infra/terraform/github-afon-core.tf`, which also records
the accepted risk they carry. Until the variables are set the pull-request job
**skips** rather than fails; `apply` fails loudly, because a plan that quietly
does not run costs a review comment and an apply that quietly does not run
leaves the cluster behind `main` with nothing red to say so.

### The config comes from Secrets Manager, not from GitHub

afon cannot synthesise without `~/.afon/config`, so CI needs a copy it can
reach. Upstream's answer - `scripts/github/secrets.sh push`, one GitHub Actions
secret per file - stays ruled out for the reason it always was: this set
carries the RDS master password and the svc user's access key.

`scripts/hdrs/ci-config.sh push` puts the file set in Secrets Manager instead,
as one JSON object of filename to contents. Re-push whenever the config
changes; `ci-config.sh diff` reports drift by digest without printing a value.

**`POLARIS_DB_PASSWORD` and `KC_DB_PASSWORD` in that bundle are ignored.** The
pipeline overwrites both from the live RDS master secret on every run, looked
up through `rds:DescribeDBInstances` rather than by a hardcoded `rds!db-` id.
That closes the rotation trap above for CI: it holds no copy, so a rotation
cannot leave it stale. If a plan ever shows those two Secrets changing, that
*is* the rotation - visible for once instead of silent.

### ⚠️ The pull-request comment is a summary, not the plan

The `svc-*` pipelines post their whole diff. This one posts resource addresses
and actions only, because afon renders config values into Terraform attributes
that no provider marks sensitive:

| Resource | Attribute | Carries |
|---|---|---|
| `helm_release.trino` | `values` | `USER_POLARIS_SECRET` |
| `kubernetes_config_map.guacamoleProvisionCm` | `data` | `JUPYTER_VNC_PASSWORD` |

Both confirmed present in the live state on 2026-09-04. Only `kubernetes_secret`
is redacted by its provider, so a verbatim plan comment would publish two live
passwords into a pull-request thread, where they outlive the branch, the run and
the log. The addresses and actions are what a reviewer needs anyway - Patch 18
was "2 to destroy" - and carry no values at all. The full diff stays in the job
log, where `::add-mask::` covers the values the run knows about.

The real fix is upstream's: put those two values in a Secret and reference it,
rather than rendering them into a chart's values and a ConfigMap.

### Three more `Map.of` sites, found by the pipeline on its first run

Patch 19 fixed the two sites it knew about. Synthesising repeatedly and
comparing - which `tofu.yml` now does on every run - found three more, **all
fixed by Patch 23**:

| Site | Tracked by Terraform? | Consequence |
|---|---|---|
| `helm_release.trino.values[0]` | **yes** | Trino shows a change on **every** plan and is upgraded on every apply |
| `null_resource.sparkClusterManifest` provisioner command | no | file churn only |
| `null_resource.sparkConnectManifest` provisioner command | no | file churn only |

Confirmed rather than assumed: the live state's Trino values document and two
fresh synths are all **equal as JSON and different as strings**. Terraform does
not diff provisioner configuration, which is why the two Spark manifests cost
nothing beyond a changed digest.

This is what briefly broke "`tofu plan` now reports `No changes`" in the Patch
18/19 notes above: `helm_release.trino` always differed. Patch 23 applies the
same fix Patch 19 used - `ORDER_MAP_ENTRIES_BY_KEYS` on the `ObjectMapper`,
rather than rewriting the maps, which handles arbitrary nesting in one line.
Verified with **eight** consecutive synths of identical input producing a
single digest.

One deliberate side effect: Trino's `additionalCatalogs` now serialise
alphabetically rather than default-catalog-first. That ordering was for
readability only, and a plan that is honest about whether anything changed is
worth more.

**The check samples, it does not prove.** It synthesises three times and
compares, not twice: measured over twelve local synths on 2026-09-04, two runs
agree by chance on about 1.5% of occasions - and the pipeline's very first CI
run drew exactly that, reporting "deterministic" against a tree known to be
otherwise. Three synths take the false-pass rate to roughly 0.02%. So a single
green run is weak evidence and a run of them is strong. Once the three sites
are ordered the check is stably green and the caveat stops mattering.

The check **blocked from Patch 23 onwards**. Until then it only warned, because
a permanent red X on every pull request teaches people to ignore CI, which is
worse than not having it. Now that the sites are ordered it is stably green, so
a failure means something new has been serialised from an unordered collection -
fix it before applying.

### What the pipeline deliberately does not do

- **The four pieces of prerequisite state afon never creates.** Still by hand,
  still first, on a fresh environment. See the table above.
- **The non-tofu deploy steps** - catalogue creation, user onboarding, the
  Spark write check. Those are `ops.*` entry points and stay operator-run.
- **Destroy.** There is no destroy path, and the deploy role does not hold the
  `ec2`/`elasticloadbalancing` delete permissions the teardown provisioners
  need. End of life follows `POC-RUNBOOK.md`, from `devops-work`.
- **Restart Polaris and Keycloak after a Secret changes.** The apply job
  *detects* it and says so in the run summary, but does not do it: restarting
  Keycloak logs every Jupyter user out, and that is the applier's call.

## Why this stack runs on OpenTofu when HDRS uses Terraform

They are two separate stacks with separate state, not one estate split across
two tools:

| | `hdrsuk/infra-aws-poc` | this repo |
|---|---|---|
| Tool | **Terraform** 1.15.8 (HDRS convention) | **OpenTofu** (Emrys's tooling) |
| Authoring | hand-written HCL | Java CDKTF → generated `cdk.tf.json` |
| State | shared S3 backend, SSE-KMS, `use_lockfile` | **the same S3 backend** (Patch 13), key `afon-core/aws.tfstate` |

`StepActions.java` invokes the binary as the literal string `"tofu"` in six
places, and `afon.sh -a setup` installs OpenTofu. Nothing about the generated
config is OpenTofu-specific - `cdk.tf.json` is plain Terraform JSON that either
tool can apply - so switching is a small patch (parameterise that binary name).

**Deliberately not doing it for the POC:** it adds fork divergence for no
functional gain. Note the state row above has changed since the original
version of this file: Patch 13 moved this stack onto the shared HDRS backend,
so the two tools now write to the same bucket (different keys). That makes
standardising more interesting, not less, if afon outlives the POC - and the
broader question (Terraform BUSL vs OpenTofu MPL-2.0 under the Linux
Foundation) is an org-wide ADR for `docs-decisions`, not something to settle
inside a fork.

## The hub keeps no state, so restarting it logs everyone out

`hub-deployment` mounts exactly one volume - its ConfigMap. There is no PVC in
the namespace at all, so `/srv/jupyterhub/jupyterhub.sqlite` lives in the
container's writable layer and is **destroyed on every restart**: the users
table, the running-server records, and the API tokens with it.

Observed 2026-08-21 (a config change needs `rollout restart` to take effect, so
this is not a rare event): after the restart the DB was empty, already-running
workspace pods were orphaned - still running, unknown to the hub - and the
browser session failed with a Guacamole "You have been disconnected", which
reads like a Guacamole or database fault and is neither.

Consequences worth knowing before the next config change: every user must log
in again; `jupyterhub token <user>` cannot mint a token for a user the DB has
never seen, so there is no scripted way to spawn a workspace after a restart;
and orphaned pods need deleting by hand.

Not fixed here - it is afon's own storage decision, and repairing a TRE's
deployment is not what this fork is for. Raise it with Emrys.

## Carried to Emrys

Things found here that are upstream's, not ours:

- **Cross-user OIDC token leak.** `_on_spawn` wrote `OIDC_TOKEN` straight into
  `spawner.environment`, and `c.KubeSpawner.environment` is a single dict shared
  by every spawner - so one user's token could stay visible in the next user's
  pod. Fixed here by copying before writing; upstream still has it.
- **Patch 18's two Services**, which force replacement on any second apply.
- **The hub's missing PVC**, above.
- **`Map.of`/`Set.of` rendered into Terraform config** (Patch 19, both sites).
  Randomised by design, so neither is safe to serialise without imposing an
  order. **And three more, found 2026-09-04:** `helm_release.trino`'s
  `values`, and the two Spark manifest `null_resource` provisioner commands. The
  Trino one is the costly one - `values` is a tracked attribute, so upstream's
  Trino release is upgraded on every apply for a document that is semantically
  unchanged.
- **Two live passwords rendered into non-sensitive attributes.**
  `USER_POLARIS_SECRET` goes into the Trino Helm release's `values` and
  `JUPYTER_VNC_PASSWORD` into the Guacamole provisioning ConfigMap. Neither is
  redacted by its provider, so both appear in full in any `terraform plan`
  output - which is why the HDRS pipeline cannot post a plan diff to a pull
  request. They belong in a Secret that is referenced, not interpolated.
- **The image-pull race and the VNC environment variable names**, both still
  wrong upstream.
- **`QUARKUS_LOG_CATEGORY__IO_QUARKUS_HTTP_ACCESS-LOG__LEVEL`** in the generated
  `polaris_env.sh`: the hyphen makes it an invalid shell identifier, so the
  container logs `not a valid identifier` and that one export silently does
  nothing on every start.

## Tracking Emrys upstream

```sh
./scripts/hdrs/check-upstream-drift.sh
```

Our `main` is a single squashed import, so `git log main..emrys/main` is
meaningless. Everything is measured against the exact commit we imported,
`56cfb474`, which the script pins **by SHA**. It deliberately is not a branch
in this repo: `main` holds only the snapshot, so a branch at that commit would
push ~1,300 commits of upstream history into `hdrsuk/afon-core`, and secret
scanning blocks that history. The SHA resolves from the `emrys` remote, which
the script fetches first, so it works in a fresh clone. Run it monthly, or
before adopting anything from upstream.

**The watchlist was wrong and is now fixed.** It omitted `JupyterHubConfig.java`
- the file carrying more HDRS code than any other, the audit perimeter and both
spawn fixes - so upstream could move underneath it and the script would report
"(none)". `JupyterHubConfig`, `NetworkConfig` and `AbstractCloudStack` are now
on it; `StepOperations` and `afon-ops/pom.xml` came off it, since we no longer
patch either.

**Position as at 2026-08-28: upstream is 0 commits ahead** - this import is
current. The previous import went 119 commits stale in one month and 267 in
five weeks, so monthly is the right cadence and a lapse is expensive.

Note upstream's CI uploads each config file into a GitHub secret via
`scripts/github/secrets.sh push`. We must not adopt that - our config carries
the RDS password and the svc-user access key, and it is local only.

## Environment ground rules

Synthetic/test data only; everything ephemeral; access via Client VPN only
(no internet-facing endpoint may be created - see Patch 7). Review by
2026-10-31.
