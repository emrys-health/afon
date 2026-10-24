# Sets up the common python env, ie the venv at scripts/python/.venv with the packages of scripts/python/pyproject.toml
# and the ipykernel 'afon', then checks it: uv, python, jupyter and the kernel are there.  Does nothing if it is set up
# already.  The scripts needing the env, eg jupyter/execute.sh, set it up themselves, so this is to do it, and find out
# what is missing, ahead of them, eg smoke/notebook.sh checks it before it runs the notebooks.
#
# usage: python/setup.sh
#
# Needs uv (https://docs.astral.sh/uv/), which finds or installs the python of the venv: 3.11 or 3.12.

AFON_ROOT="${AFON_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv is not installed, which sets up the python env: see https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi

source "$AFON_ROOT/scripts/python/python-source.sh"

PYTHON_DIR="$AFON_ROOT/scripts/python"
for tool in python jupyter; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "The python env at $PYTHON_DIR/.venv has no $tool" >&2
    exit 1
  fi
done
if ! jupyter kernelspec list 2>/dev/null | grep -q "^\s*afon\s"; then
  echo "The ipykernel 'afon' is not registered" >&2
  exit 1
fi
echo "Python env ready: $(python --version) at $PYTHON_DIR/.venv, with jupyter and the ipykernel 'afon'"
