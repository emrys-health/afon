# Setup Local Env

## Bash 

Env setup requires *bash* installed on your machine.  

For linux and macOS bash is provided.  

To set up on a given OS, run: 

```shell
afon-cli.sh local-setup -c <cloud>
```

Git, java, maven and the cloud clis are installed with apt (linux) or brew (macOS):
- git
- jdk (openjdk 17)
- maven
- awscli
- azure-cli
- google-cloud-cli gke-gcloud-auth-plugin (on linux Google's apt repo is added if missing)

All the other tools are downloaded with `curl` (needing `curl`, `tar` and `unzip`):
- gh
- docker (the client only: the engine, ie Docker Desktop, Colima or dockerd, is a prerequisite)
- opentofu, kubectl, hubble, jq, yq, helm
- kind and cosign (for the image signing acceptance test and `docker-image --sign`; they are in no default apt repository, and brew offers only their current versions)
- eksctl, kubelogin

Of the cloud cli packages, only the ones relative to the `<cloud>` specified are installed.
If no `<cloud>` is specified all are installed.

**NOTE:** JDK 17 is currently enforced by the Maven build.  Brew's `openjdk@17` is keg-only, so it is not linked into the PATH: set `JAVA_HOME` to its location (`$(brew --prefix openjdk@17)`).

To show what is currently installed, checked against the pinned versions:
```shell
afon-cli.sh local-versions -c <cloud>
```

The tool versions are pinned in `scripts/local/pinned.sh`, to make local setups reproducible.
`local-setup` installs the pinned version of each tool, unless it is already at that version:
- downloaded binaries are put in `/usr/local/bin` (override with `BIN_DIR`, using `sudo` if it is not writable), replacing any file of
  that name there if the tool is not at the pinned version;
- apt packages are installed as `package=<pinned version>` and held with `apt-mark hold`.  If apt offers no version starting with the pin,
  a warning is printed and the version apt offers is installed, which `local-versions` then reports as a `MISMATCH`;
- brew formulae are pinned with `brew pin` once at the pinned version, otherwise a warning is printed, as brew only offers the
  current version.  A cask, ie gcloud, cannot be pinned.

For each downloaded binary, `local-setup` says where it is: `<tool> is up to date in <dir>` if it is in the directory it installs to,
`<tool> is up to date at <path> (release binaries are installed in <dir>)` if it is elsewhere, eg a brew copy, or `Installing <tool> in <dir>` when
it installs it.

Another copy of a tool earlier on the PATH, eg brew's, shadows the pinned one: `local-setup` warns, so remove it (eg `brew uninstall`).

### Without sudo

With `NO_SUDO=true`, `local-setup` never runs `sudo`:
```shell
NO_SUDO=true afon-cli.sh local-setup -c <cloud>
```
- downloaded binaries are put in `~/.local/bin` (or `BIN_DIR`), which is created if needed and must be on the `PATH`; it stops with an error if
  the directory is not writable;
- what needs apt (git, java, maven and the cloud clis on linux) is not installed, nor are the Azure and Google apt repositories added: for each
  that is not at the pinned version a warning says to install the package yourself.

It ends by checking the installed versions against the pins.
`local-versions` reports each tool as `OK`, `MISMATCH`, `MISSING` or `UNPINNED`.
A pin is a version prefix, eg `17.0.20` for java matches any 17.0.20.x.
A pin can be overridden per platform, eg `PINNED_git_linux`, as apt and brew offer different versions of git and the cloud clis.
On Ubuntu a pin can also be overridden per release, eg `PINNED_git_ubuntu2204` for 22.04, which takes precedence over the linux one.

## Docker 

To build the Docker container, run:
```shell
cd scripts/docker/afon
docker build -t emryshealth/afon .
```

To run the Docker container, link your afon dir to $HOME/afon and use following docker command:
```shell
ln -s <your afon dir> $HOME/afon
docker run -it -v $HOME/.kube:$HOME/.kube -v $HOME/.config/gcloud:$HOME/.config/gcloud -v $HOME/.azure:$HOME/.azure -v $HOME/.aws:$HOME/.aws  -v $HOME/afon/:$HOME/afon  -v $HOME/.m2:$HOME/.m2 -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) -e HOST_USER=$(whoami)  emryshealth/afon