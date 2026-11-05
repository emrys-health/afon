# Afon docker images

- `base`: the cli tools Afon uses, at pinned versions, including maven to build Afon from source.
- `cli`: the runtime image, being the base image with the pre-built Afon CLI zip installed.

Build them with `build.sh` (see `build.sh --help`).  See [Docker images](../../../docs/docker.md) for building,
tagging, pushing and running them.
