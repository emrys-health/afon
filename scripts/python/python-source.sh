# Common Python setup, shared by any script needing a python env (eg jupyter/execute.sh).
# Sets up (once) and activates the common venv at scripts/python/.venv via uv, syncing
# scripts/python/pyproject.toml, and registers an ipykernel kernelspec named 'afon'.

PYTHON_KERNEL="afon"
PYTHON_DIR="$AFON_ROOT/scripts/python"

cd "$PYTHON_DIR"
if [ ! -d .venv ]; then
  uv venv
fi
source .venv/bin/activate
uv sync

# The kernelspec is per user, so it outlives the checkout: register it if it is missing, or if it runs another python than
# that of this venv, eg after the checkout was moved or renamed, when it would fail with 'No such file or directory'.
REGISTERED_PYTHON="$(jupyter kernelspec list --json 2>/dev/null | python -c '
import json, sys
try:
    print(json.load(sys.stdin).get("kernelspecs", {}).get(sys.argv[1], {}).get("spec", {}).get("argv", [""])[0])
except ValueError:
    print("")
' "$PYTHON_KERNEL")"
if [ "$REGISTERED_PYTHON" != "$(python -c 'import sys; print(sys.executable)')" ]; then
  [ -n "$REGISTERED_PYTHON" ] && echo "The ipykernel '$PYTHON_KERNEL' runs $REGISTERED_PYTHON, not the python of this venv"
  echo "Registering ipykernel '$PYTHON_KERNEL'..."
  python -m ipykernel install --user --name "$PYTHON_KERNEL" --display-name "$PYTHON_KERNEL"
fi
cd "$AFON_ROOT"
