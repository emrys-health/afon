#!/usr/bin/env bash
source $AFON_ROOT/scripts/python/python-source.sh

NOTEBOOK="${1:-}"
OUTPUT="${2:-$NOTEBOOK}"

if [ -z "$NOTEBOOK" ]; then
  echo "Action jupyter-execute requires a notebook path, eg: jupyter-execute <notebook.ipynb>"
  exit 0
fi
if [ ! -f "$NOTEBOOK" ]; then
  echo "Notebook not found: $NOTEBOOK"
  exit 1
fi

# Each cell may run for at most AFON_NOTEBOOK_CELL_TIMEOUT seconds (default 300), so that a cell that hangs, eg on a
# connection that is never answered, fails the notebook with its traceback rather than blocking the run for ever.
CELL_TIMEOUT="${AFON_NOTEBOOK_CELL_TIMEOUT:-300}"

echo "Executing $NOTEBOOK (kernel '$PYTHON_KERNEL', cell timeout ${CELL_TIMEOUT}s)..."
# Errors do not stop the run, so that the notebook is written with its outputs even when a cell fails, which a run from a machine has no other way to show: the outputs
# of a notebook with an error are printed below, and the script fails
jupyter execute --kernel_name="$PYTHON_KERNEL" --timeout="$CELL_TIMEOUT" --allow-errors --output="$OUTPUT" "$NOTEBOOK" || exit 1
echo "Output written to $OUTPUT"
python - "$OUTPUT" <<'PY'
import json, sys
notebook = json.load(open(sys.argv[1]))
cells = [c for c in notebook["cells"] if c["cell_type"] == "code"]
errors = [o for c in cells for o in c.get("outputs", []) if o["output_type"] == "error"]
if errors:
    print("The notebook failed: its outputs follow")
    for n, cell in enumerate(cells, 1):
        for out in cell.get("outputs", []):
            if out["output_type"] == "stream":
                print("".join(out["text"]), end="")
            elif out["output_type"] == "error":
                print(f"[cell {n}] {out['ename']}: {out['evalue']}")
    sys.exit(1)
PY
STATUS=$?
[ "$STATUS" -eq 0 ] && echo "Done."
exit "$STATUS"
