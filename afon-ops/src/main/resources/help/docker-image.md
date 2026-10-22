docker-image

Prints the reference of a Docker image of docker-images.yaml, as AFON_DOCKER_IMAGE=<image>:<tag>[@<digest>], for the
scripts that need one.  The images are those of the docker-images.yaml in the config dir, if there is one, else of the one
bundled in the jar.

--export writes the bundled docker-images.yaml to the config dir, to edit it: the file in the config dir replaces the bundled
one entirely.  It never overwrites a file, so remove it first to export the bundled images again.

Usage:
  afon-cli.sh docker-image <name>
  afon-cli.sh docker-image --export

<name> is the name of an image of the file, eg postgres-alpine.
