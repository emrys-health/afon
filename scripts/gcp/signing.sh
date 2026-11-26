#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh gcp

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <create|delete|show>"
  echo "Creates, outside the stack, what signing the Docker images needs on GCP, see docs/stacks.md#image-signing:"
  echo "- the asymmetric Cloud KMS key (EC P-256, sign and verify) of GCP_COSIGN_KEY, ie"
  echo "  gcpkms://projects/<project>/locations/<location>/keyRings/<ring>/cryptoKeys/<key>, and its key ring.  Only the deployer, ie the"
  echo "  gcloud account that runs this, is given the right to sign with it and to read its public key."
  echo "- the Artifact Registry repository of GCP_IMAGE_REGISTRY, ie <location>-docker.pkg.dev/<project>/<repository>[/<path>], with"
  echo "  untagged images deleted after a time, and the deployer allowed to write to it.  The cluster of a stack reads from it through"
  echo "  Workload Identity if GCP_WORKLOAD_IDENTITY=true, see docs/stacks.md."
  echo "It is idempotent.  Afterwards run: afon-cli.sh signing-public-key gcp"
}

ACTION="${1:-}"
case "$ACTION" in
  create|delete|show) ;;
  *) usage; exit 1 ;;
esac

# Untagged images, ie the manifests that a copy or a signature replaced, are deleted after this long.  Artifact Registry leaves those that a
# tagged image index refers to, ie the images of each platform of a multi-architecture image
UNTAGGED_DAYS=14

### The registry and the key, from the properties

REGISTRY_HOST="${GCP_IMAGE_REGISTRY:-}"
if [[ ! "$REGISTRY_HOST" =~ ^([a-z0-9-]+)-docker\.pkg\.dev/([a-z][a-z0-9.:-]*)/([a-z][a-z0-9-]*)(/.*)?$ ]]; then
  echo "-- GCP_IMAGE_REGISTRY must be an Artifact Registry repository, ie <location>-docker.pkg.dev/<project>/<repository>[/<path>], not '$REGISTRY_HOST'"
  exit 1
fi
REGISTRY_LOCATION="${BASH_REMATCH[1]}"
REGISTRY_PROJECT="${BASH_REMATCH[2]}"
REPOSITORY="${BASH_REMATCH[3]}"

# gcpkms://projects/<project>/locations/<location>/keyRings/<ring>/cryptoKeys/<key>, which cosign takes, with or without /versions/<n>, which
# this does not need: the key signs with its primary version
KEY_REF="${GCP_COSIGN_KEY:-}"
if [[ ! "$KEY_REF" =~ ^gcpkms://projects/([a-z][a-z0-9.:-]*)/locations/([a-z0-9-]+)/keyRings/([A-Za-z0-9_-]+)/cryptoKeys/([A-Za-z0-9_-]+)(/versions/[0-9]+)?$ ]]; then
  echo "-- GCP_COSIGN_KEY must be gcpkms://projects/<project>/locations/<location>/keyRings/<ring>/cryptoKeys/<key>, not '$KEY_REF'"
  exit 1
fi
KEY_PROJECT="${BASH_REMATCH[1]}"
KEY_LOCATION="${BASH_REMATCH[2]}"
KEY_RING="${BASH_REMATCH[3]}"
KEY_NAME="${BASH_REMATCH[4]}"
KEY_ARGS=(--project "$KEY_PROJECT" --location "$KEY_LOCATION" --keyring "$KEY_RING")

# The gcloud account that signs, as a member of an IAM policy: a service account or a user.  cosign signs with the Application Default Credentials,
# which are those of gcp-login, ie the same account.
CALLER="$(gcloud config get-value account 2>/dev/null)"
if [ -z "$CALLER" ]; then
  echo "-- Not logged in: run afon-cli.sh gcp-login"
  exit 1
fi
case "$CALLER" in
  *.gserviceaccount.com) DEPLOYER="serviceAccount:$CALLER" ;;
  *) DEPLOYER="user:$CALLER" ;;
esac

# Whether the key is there, ie it can be read: never fails, as the launcher runs scripts with -e, which would end this one, with no message, at
# the assignment of a failed command
key_exists() {
  gcloud kms keys describe "$KEY_NAME" "${KEY_ARGS[@]}" --format="value(name)" >/dev/null 2>&1
}
repository_exists() {
  gcloud artifacts repositories describe "$REPOSITORY" --project "$REGISTRY_PROJECT" --location "$REGISTRY_LOCATION" \
    --format="value(name)" >/dev/null 2>&1
}
# Enables the API in the project if it is not enabled yet; if that is refused, says who can
ensure_api() {
  local API="$1" PROJECT="$2"
  if [ -n "$(gcloud services list --enabled --project "$PROJECT" --filter="config.name=$API" --format="value(config.name)" 2>/dev/null)" ]; then
    echo "- API $API is enabled in $PROJECT"
    return 0
  fi
  echo "- Enabling the API $API in $PROJECT"
  gcloud services enable "$API" --project "$PROJECT" && return 0
  echo "-- Could not enable $API: have an admin run 'gcloud services enable $API --project $PROJECT', or give '$CALLER' roles/serviceusage.serviceUsageAdmin, then run this again"
  return 1
}
# The versions of the key that can sign, by number
enabled_versions() {
  gcloud kms keys versions list --key "$KEY_NAME" "${KEY_ARGS[@]}" --filter="state=ENABLED" --format="value(name)" 2>/dev/null \
    | sed 's|.*/||' || true
}

case "$ACTION" in
  create)
    # Only the APIs that are not enabled yet, as enabling needs a role (roles/serviceusage.serviceUsageAdmin) that an admin who has enabled them
    # leaves the deployer without
    ensure_api cloudkms.googleapis.com "$KEY_PROJECT" || exit 1
    ensure_api artifactregistry.googleapis.com "$REGISTRY_PROJECT" || exit 1

    ## Key

    if gcloud kms keyrings describe "$KEY_RING" --project "$KEY_PROJECT" --location "$KEY_LOCATION" >/dev/null 2>&1; then
      echo "- Key ring '$KEY_RING' already exists, skipping creation"
    else
      echo "- Creating the key ring '$KEY_RING' in $KEY_LOCATION"
      gcloud kms keyrings create "$KEY_RING" --project "$KEY_PROJECT" --location "$KEY_LOCATION" || exit 1
    fi
    if key_exists; then
      echo "- Key '$KEY_NAME' already exists, skipping creation"
      # A key whose versions were destroyed, by delete, signs nothing: it gets a new one, which a key cannot be without
      if [ -z "$(enabled_versions)" ]; then
        echo "- It has no version that can sign, creating one"
        gcloud kms keys versions create --key "$KEY_NAME" "${KEY_ARGS[@]}" --primary || exit 1
      fi
    else
      echo "- Creating the EC P-256 signing key '$KEY_NAME'"
      gcloud kms keys create "$KEY_NAME" "${KEY_ARGS[@]}" --purpose asymmetric-signing --default-algorithm ec-sign-p256-sha256 \
        --labels afon=image-signing || exit 1
    fi
    # Signing, verifying and reading the public key are given to the deployer on the key alone, not on the key ring or the project, and no other
    # member is given them here: so no other account can sign.  Whoever can change the policy of the key, or of what it is in, can still give
    # itself the right to, which is the case of any key, so who may is for IAM to restrict.
    echo "- Letting '$DEPLOYER' sign with the key"
    gcloud kms keys add-iam-policy-binding "$KEY_NAME" "${KEY_ARGS[@]}" --member "$DEPLOYER" --role roles/cloudkms.signerVerifier \
      --format="value(etag)" >/dev/null || exit 1

    ## Registry

    if repository_exists; then
      echo "- Repository '$REPOSITORY' already exists, skipping creation"
    else
      echo "- Creating the Docker repository '$REPOSITORY' in $REGISTRY_LOCATION"
      gcloud artifacts repositories create "$REPOSITORY" --project "$REGISTRY_PROJECT" --location "$REGISTRY_LOCATION" \
        --repository-format docker --description "Afon: the Docker images, mirrored and signed" --labels afon=image-signing || exit 1
    fi
    WORK_DIR="$(mktemp -d)"
    trap 'rm -rf "$WORK_DIR"' EXIT
    cat > "$WORK_DIR/cleanup.json" <<CLEANUP
[
  {
    "name": "delete-untagged",
    "action": {"type": "Delete"},
    "condition": {"tagState": "untagged", "olderThan": "$((UNTAGGED_DAYS * 86400))s"}
  }
]
CLEANUP
    echo "- Deleting the untagged images after $UNTAGGED_DAYS days"
    gcloud artifacts repositories set-cleanup-policies "$REPOSITORY" --project "$REGISTRY_PROJECT" --location "$REGISTRY_LOCATION" \
      --policy "$WORK_DIR/cleanup.json" --no-dry-run >/dev/null || exit 1
    echo "- Letting '$DEPLOYER' write to the repository"
    gcloud artifacts repositories add-iam-policy-binding "$REPOSITORY" --project "$REGISTRY_PROJECT" --location "$REGISTRY_LOCATION" \
      --member "$DEPLOYER" --role roles/artifactregistry.writer --format="value(etag)" >/dev/null || exit 1

    echo "- Registry '$GCP_IMAGE_REGISTRY' and key '$KEY_REF' ready."
    echo "- Next: afon-cli.sh signing-public-key gcp, then docker-image --sign --cloud gcp"
    ;;

  delete)
    if repository_exists; then
      echo "- Deleting repository '$REPOSITORY', and the images in it"
      gcloud artifacts repositories delete "$REPOSITORY" --project "$REGISTRY_PROJECT" --location "$REGISTRY_LOCATION" --quiet || exit 1
    else
      echo "- Repository '$REPOSITORY' does not exist, skipping"
    fi
    if key_exists; then
      VERSIONS="$(enabled_versions)"
      if [ -z "$VERSIONS" ]; then
        echo "- Key '$KEY_NAME' has no version left to destroy, skipping"
      fi
      for VERSION in $VERSIONS; do
        echo "- Scheduling the destruction of version $VERSION of the key '$KEY_NAME', which can be restored until then"
        gcloud kms keys versions destroy "$VERSION" --key "$KEY_NAME" "${KEY_ARGS[@]}" >/dev/null || exit 1
      done
      echo "- The key and its key ring stay, as Cloud KMS cannot delete them: create makes a new version of the key"
    else
      echo "- Key '$KEY_NAME' does not exist, skipping"
    fi
    ;;

  show)
    if key_exists; then
      echo "- Key '$KEY_NAME' in the key ring '$KEY_RING':"
      gcloud kms keys describe "$KEY_NAME" "${KEY_ARGS[@]}" --format="value(purpose, primary.algorithm, primary.state)" \
        | awk '{ printf "  purpose %s, algorithm %s, primary version %s\n", $1, $2, $3 }'
      echo "- It may be signed with by:"
      gcloud kms keys get-iam-policy "$KEY_NAME" "${KEY_ARGS[@]}" --format=json \
        | jq -r '.bindings[]? | select(.role == "roles/cloudkms.signerVerifier") | .members[] | "  " + .' \
        || echo "  (cannot read the policy of the key)"
    else
      echo "- Key '$KEY_NAME' does not exist, or cannot be read"
    fi
    if repository_exists; then
      echo "- Repository '$REPOSITORY' exists"
    else
      echo "- Repository '$REPOSITORY' does not exist"
    fi
    ;;
esac
