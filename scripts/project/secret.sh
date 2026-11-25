#!/usr/bin/env bash
# Makes the secret of a client of a project, its TRE client or its Git client: the client authenticates to the central identity with it,
# the identity is given it when the project is registered, and the project's config has it as OAUTH2_CLIENT_SECRET, see docs/projects.md.
set -euo pipefail

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <project> <client> [--force]"
  echo "Generates a random secret (256 bits, as hex) for the client of the project, <project>-<client>, and writes it, readable by its owner only, to"
  echo "  \$AFON_HOME/projects/<project>/secrets/<client>.secret"
  echo "manage-project-stack configure puts the TRE client's in the project's config, and manage-identity create gives the identity the same."
  echo "<project> is the id of the project, a lower-case DNS label of at most 30 characters, eg xyz-yyyy-nnnn."
  echo "<client> is tre, the client of the project's TRE, or git, the client of its own Git, which a project has only if its file has stacks.git."
  echo "It stops if the file exists already, as the identity and the config were given that secret, unless --force replaces it."
  echo "Needs openssl."
}

PROJECT=""
CLIENT=""
FORCE=false
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --force) FORCE=true; shift ;;
    -*) echo "- Unknown option '$1'"; usage; exit 1 ;;
    *)
      if [ -z "$PROJECT" ]; then
        PROJECT="$1"
      elif [ -z "$CLIENT" ]; then
        CLIENT="$1"
      else
        echo "- Only a project and a client are given, not also '$1'"; usage; exit 1
      fi
      shift ;;
  esac
done

if [ -z "$PROJECT" ] || [ -z "$CLIENT" ]; then
  usage; exit 1
fi
case "$CLIENT" in
  tre|git) ;;
  *) echo "- '$CLIENT' is not a client of a project: tre or git"; usage; exit 1 ;;
esac
# the id names a dir, so it is checked as ProjectStackId does, which also keeps it from naming a path
if [ "${#PROJECT}" -gt 30 ] || ! [[ "$PROJECT" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]]; then
  echo "- '$PROJECT' is not a project id: a lower-case DNS label of at most 30 characters, eg xyz-yyyy-nnnn"
  exit 1
fi
if ! command -v openssl > /dev/null 2>&1; then
  echo "- openssl is needed and is not installed"
  exit 1
fi

PROJECT_DIR="$AFON_HOME/projects/$PROJECT"
SECRET_FILE="$PROJECT_DIR/secrets/$CLIENT.secret"

if [ "$FORCE" != "true" ] && [ -e "$SECRET_FILE" ]; then
  echo "- $SECRET_FILE exists already: the identity and the project's config were given that secret, so it is not replaced"
  echo "  Use --force to replace it, then configure the project and make it in the identity again: manage-project-stack configure $PROJECT, manage-identity create $PROJECT"
  exit 1
fi

mkdir -p "$(dirname "$SECRET_FILE")"
# readable by its owner only, from its creation
(
  umask 077
  openssl rand -hex 32 > "$SECRET_FILE"
)
chmod 600 "$SECRET_FILE"

echo "- Secret of the $CLIENT client of $PROJECT, $PROJECT-$CLIENT: $SECRET_FILE (readable by its owner only)"
if [ ! -f "$PROJECT_DIR/project.yaml" ]; then
  echo "- There is no $PROJECT_DIR/project.yaml yet: write it, see docs/projects.md"
fi
echo "- Then: afon-cli.sh manage-project-stack configure $PROJECT, and manage-identity create $PROJECT"
