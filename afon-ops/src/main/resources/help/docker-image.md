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

--sign signs the images with cosign, at the digests they are pinned to, with the key of one cloud, --cloud <aws|azure|gcp> or else
AFON_CLOUD, and so the images need a digest.  It needs <CLOUD>_IMAGE_REGISTRY, the registry with the path in it to keep the images in, eg
123.dkr.ecr.eu-west-2.amazonaws.com/emrys, and <CLOUD>_COSIGN_KEY, the reference of the key as cosign sign --key takes it, eg
awskms:///alias/afon-image-signing, which only the deployer role should be able to use.  The first-party images are signed where they
are; the others (upstream and third-party) are first copied, at their digest, to the registry under the path of the image without its
own registry, eg quay.io/keycloak/keycloak to <registry>/keycloak/keycloak, and the copy is signed, after checking that it has the digest
of the source.  Without names it signs each image that has a digest.  It needs Docker, cosign 3, network access and the credentials of the
registry and the key, with COSIGN_PASSWORD set to the password of a key file (empty if it has none).  Signing an image again adds a signature.  --report <file> writes the record of the signing, JSON if the file is named *.json, Markdown if *.md, even when
signing stops on an error: the cloud, registry and key reference, when, and each image signed, with its source, the image at its digest that
was signed, and whether it was copied, for a CI job to keep.  The signatures are not uploaded to a transparency log.

--verify --signatures checks instead that the images have a signature that verifies with the public key of one cloud (--cloud <aws|azure|gcp> or
else AFON_CLOUD), <CLOUD>_COSIGN_PUBLIC_KEY, in the place --sign puts it: the image itself if it is ours, else its copy in <CLOUD>_IMAGE_REGISTRY.  It runs
cosign verify, so it needs cosign and the registry, but not the private key or a cluster.  Without names it checks each image that has a digest; every image is
checked even if one fails, and the exit code is 1, naming them with the reason (not signed, or signed with another key), if any does.  --report <file> writes
the result, JSON if it is named *.json, Markdown if *.md, even when the check fails.

--public-key prints the public key of the cosign key of one cloud (--cloud <aws|azure|gcp> or else AFON_CLOUD), <CLOUD>_COSIGN_KEY, as the
line <CLOUD>_COSIGN_PUBLIC_KEY=<the base64 of the key, a single word> to put in the config of the stack, which Kyverno verifies the
images with: it needs no access to the key, only the registry, when it checks an image.  It runs cosign public-key --key, so it needs cosign and
the credentials of the key, and for a key file its password in COSIGN_PASSWORD (empty if it has none), as the command takes no input, and prints nothing else but the line, for a script to take.

--charts reports the images that the Helm charts of the stacks pull, which docker-images.yaml does not list, as they are the charts' own.
It reads the helm releases of the plan synthesised for each stack (so with the values the stack gives them, eg an image tag it
overrides) and renders each chart with helm template, which needs helm installed but no cluster.  For each release it lists the
images, saying if each is pinned by digest, has only a tag or has none, and which are in docker-images.yaml.  The images only
helm test pods use are counted, not listed.  With no stack ids, it takes the stacks that are synthesised (eg by validate-stack
<stack-id>); --report <file> writes the same as JSON or Markdown, by the extension.  Exits with 1 if a release cannot be rendered.

Usage:
  afon-cli.sh docker-image <name>
  afon-cli.sh docker-image --export
  afon-cli.sh docker-image --pin [--file <file>] [<name>...]
  afon-cli.sh docker-image --verify [--file <file>] [--report <file>] [<name>...]
  afon-cli.sh docker-image --verify --signatures [--cloud <aws|azure|gcp>] [--report <file>] [<name>...]
  afon-cli.sh docker-image --sign [--cloud <aws|azure|gcp>] [--report <file>] [<name>...]
  afon-cli.sh docker-image --public-key [--cloud <aws|azure|gcp>]
  afon-cli.sh docker-image --charts [--report <file>] [<stack-id>...]

<name> is the name of an image of the file, eg postgres-alpine.
