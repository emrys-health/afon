# Docker images

Three Docker images give a ready-made environment for running Afon, with the right version of every tool, reproduce a
[local setup](local_setup.md):

- `emryshealth/afon-base`: Ubuntu 22.04 with the cli tools Afon uses, at pinned versions: java, maven, node,
  tofu, helm, yq, jq, dig, and the aws (with eksctl), azure (with kubectl and kubelogin) and gcloud clis.
- `emryshealth/afon-cli`: the runtime image, being the base image with the pre-built [Afon CLI](afon_cli.md) zip
  installed in `/opt/afon/cli` (`AFON_CLI_DIR`) and on the `PATH`.  The zip is built outside of the image.
- `emryshealth/afon-azure-agent`: a self-hosted [Azure Pipelines agent](#azure-pipelines-agent), being the cli image with an
  entrypoint that registers and runs the agent.

All can be built for `linux/amd64` or `linux/arm64`.  Their Dockerfiles are in `scripts/docker/afon/base`,
`scripts/docker/afon/cli` and `scripts/docker/afon/azure-agent`.

## Building

The images are built with `scripts/docker/afon/build.sh`, from anywhere in the repo:
```shell
scripts/docker/afon/build.sh                            # all the images
scripts/docker/afon/build.sh base                       # the base image only
scripts/docker/afon/build.sh cli                        # the cli image only
scripts/docker/afon/build.sh azure-agent                # the agent image only
scripts/docker/afon/build.sh --platform linux/arm64     # for arm64, eg native on Apple silicon
```

The cli image is built on the base image of the same version, and the agent image on the cli image of the same version, so after a new commit
build them in that order.
The base image's tool layers are cached, so rebuilding it is quick unless its Dockerfile changed.

The cli image installs the pre-built `afon-distribution/target/afon-<version>-cli.zip`, or the one given with
`--zip <zip>`; the build fails if it does not exist.  Build the zip first, eg
`afon.sh -bg "clean package" -bp cli,distribution`, or with maven in the base image.  The zip is the only build
context of the cli image.

### Versions and tags

The version is the project version in the root `pom.xml`, as read by `mvn help:evaluate`: `x-SNAPSHOT` on `main`, and the release version on a
release commit:

| Built from                            | Version                  |
|---------------------------------------|--------------------------|
| `main`                                | `1.0-SNAPSHOT`           |
| a release commit, eg of `1.0-beta-9`  | `1.0-beta-9`             |
| ... with local changes                | `1.0-SNAPSHOT-dirty`     |

Each image is tagged with:

- the version;
- the short commit, eg `a7358e58`, unless there are local changes;
- `latest`, only on a release (a version that is not a SNAPSHOT) with no local changes.

A SNAPSHOT tag is overwritten by each build, so use the short commit tag to pin an exact build.

The images carry the standard `org.opencontainers.image.*` labels: the version, the commit, the source repo and
the build time.

### Helper Flags

`--push` pushes every tag of the built images.  `--registry` prefixes the image names with a registry, eg to push to
an Azure Container Registry:
```shell
az login
scripts/docker/afon/build.sh --registry myacr.azurecr.io --push
```
which pushes `myacr.azurecr.io/emryshealth/afon-base`, `afon-cli` and `afon-azure-agent`.  For a
registry ending in `.azurecr.io`, the script logs in with `az acr login` before building.  For any other registry,
`docker login` to it first.

### Running

The environment can invoked like so:

```sh
docker run -it --rm \
  -v "~/.afon/config:/home/afon/.afon/config:ro" \
  emryshealth/afon-cli:1.0-beta-7 \
  'afon-cli.sh validate-stack azure-polaris' # or `/bin/bash` for an interactive session
```
