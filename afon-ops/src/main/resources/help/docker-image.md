docker-image

Prints the reference of a Docker image of docker-images.yaml, as AFON_DOCKER_IMAGE=<image>:<tag>[@<digest>], for the
scripts that need one.  The images are those of the docker-images.yaml in the config dir, if there is one, else of the one
bundled in the jar.

--export writes the bundled docker-images.yaml to the config dir, to edit it: the file in the config dir replaces the bundled
one entirely.  It never overwrites a file, so remove it first to export the bundled images again.

--pin looks up in their registries the digests that the tags of the images point to now (with docker buildx, so it needs Docker and
network access), and writes them to the images file, leaving the rest of it as it is.  Without names, it refreshes the digest of
each image that has one; with names, it pins those, adding the digest to an image that has none.  It pins the docker-images.yaml of
the config dir (export the bundled one to it first with --export), else the one given with --file.  Nothing is written if a lookup fails.  An
image whose tag is a placeholder, eg ${polaris.version}, or that has an 'unpinned' reason in the file, stays unpinned, as a digest would outlive the version.  After changing the tag
of an image, run it with the name of the image to update its digest.

--verify only checks that the digest of each image that has one is that its tag points to now, writing nothing, and exits with 1,
naming the images, if not: that is, if a tag was changed without its digest, or published again by its registry.  With names,
it checks those, and an image with no digest then fails.  It checks the images in effect (the file of the config dir, else the bundled
one), or the file given with --file.  --report <file> also writes a report of the result to the file, JSON if it is named *.json, Markdown if *.md, even when the
check fails, eg for a CI job to keep: the images checked with their status (ok, mismatch, unpinned or error) and digests, and
the images not checked.  Like --pin, it needs Docker and network access.  It lists the images with no digest that it did not check, with the reason the file gives
for them (the 'unpinned' key of the image), else that the file has no digest.

Usage:
  afon-cli.sh docker-image <name>
  afon-cli.sh docker-image --export
  afon-cli.sh docker-image --pin [--file <file>] [<name>...]
  afon-cli.sh docker-image --verify [--file <file>] [--report <file>] [<name>...]

<name> is the name of an image of the file, eg postgres-alpine.
