# Docker images

Two Docker images give a ready-made environment for running Afon, with the right version of every tool, without a
[local setup](local_setup.md):

- `emryshealth/afon-base-image`: Ubuntu 22.04 with the cli tools Afon uses, at pinned versions: java, maven, node,
  tofu, helm, yq, jq, dig, and the aws (with eksctl), azure (with kubectl and kubelogin) and gcloud clis.
- `emryshealth/afon-cli`: the base image with the [Afon CLI](afon_cli.md) built from the source, installed in
  `/opt/afon/cli` (`AFON_CLI_DIR`) and on the `PATH`.

Both are built for `linux/amd64` or `linux/arm64`.  Their Dockerfiles are in `scripts/docker/afon/base` and
`scripts/docker/afon/cli`.

## Building

The images are built with `scripts/docker/afon/build.sh`, from anywhere in the repo:
```shell
scripts/docker/afon/build.sh                            # both images
scripts/docker/afon/build.sh base                       # the base image only
scripts/docker/afon/build.sh cli                        # the cli image only
scripts/docker/afon/build.sh --platform linux/arm64     # for arm64, eg native on Apple silicon
```

The cli image is built on the base image of the same version, so after a new commit build both, or `base` first.
The base image's tool layers are cached, so rebuilding it is quick unless its Dockerfile changed.

The cli image is built from the repo root, so `docker build` needs the repo root as its context, and
`scripts/docker/afon/cli/Dockerfile.dockerignore` keeps `target`, `node_modules`, `.git` etc. out of it.  The
build runs `mvn install -Pcli` without the tests, which need fully a fully configured environment.

### Versions and tags

The version is taken from git, as `git describe` over the `afon-*` release tags, less the `afon-` prefix:

| Built from                         | Version                          |
|------------------------------------|----------------------------------|
| the release tag `afon-1.0-beta-7`  | `1.0-beta-7`                     |
| 126 commits after it               | `1.0-beta-7-126-ga7358e58`       |
| ... with local changes             | `1.0-beta-7-126-ga7358e58-dirty` |

Each image is tagged with:

- the version;
- the short commit, eg `a7358e58`, unless there are local changes;
- `latest`, only on a release tag with no local changes.

The images carry the standard `org.opencontainers.image.*` labels: the version, the commit, the source repo and
the build time.

### Helper Flags


`--push` pushes every tag of the built images.  `--registry` prefixes the image names with a registry, eg to push to
an Azure Container Registry:
```shell
az login
scripts/docker/afon/build.sh --registry myacr.azurecr.io --push
```
which pushes `myacr.azurecr.io/emryshealth/afon-base-image` and `myacr.azurecr.io/emryshealth/afon-cli`.  For a
registry ending in `.azurecr.io`, the script logs in with `az acr login` before building.  For any other registry,
`docker login` to it first.
