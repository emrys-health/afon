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

if ! jupyter kernelspec list 2>/dev/null | grep -q "^\s*$PYTHON_KERNEL\s"; then
  echo "Registering ipykernel '$PYTHON_KERNEL'..."
  python -m ipykernel install --user --name "$PYTHON_KERNEL" --display-name "$PYTHON_KERNEL"
fi
cd "$AFON_ROOT"
