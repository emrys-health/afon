# Vendored Helm charts

Every `helm_release` in `afon-cdk` installs from a tarball in this directory rather than
pulling from a chart repository at apply time. `CdkUtils.vendoredChart` stages the chosen
file through a `TerraformAsset`, so the generated `cdk.tf.json` refers to it by a path
relative to the stack output directory.

Two reasons:

- **Resilience.** Chart repositories are all GitHub Pages, and a repository's `index.yaml`
  can run to megabytes (Cilium's is ~1.7MB, Grafana's ~4MB). One reset mid-read fails the
  whole apply with the Helm provider's misleading "not a valid chart repository" error.
- **Reproducibility.** The bytes are pinned, which a version constraint alone does not
  guarantee. CI (`.github/workflows/aws-stack-linux.yml`) has no helm CLI and no populated
  helm cache, so it could not rely on one anyway.

Subchart dependencies are bundled inside these tarballs (kube-prometheus-stack carries
grafana, kube-state-metrics and the exporters; loki carries minio and the operators), so
installs need no network access to a chart repository at all.

## Adding or bumping a chart

```sh
helm pull <chart> --repo <repository-url> --version <version> -d afon-cdk/src/main/resources/charts
```

Then point the release at the new file name and delete the old tarball:

```java
.chart(CdkUtils.vendoredChart(scope, "<id>Chart", "<chart>-<version>.tgz"))
```

There is no `.version()` on these releases — the tarball *is* the version, and the provider
reads it from the chart's own `Chart.yaml`. Keep the version in the file name so a bump is
visible in the diff.
