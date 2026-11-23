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
jupyter execute --kernel_name="$PYTHON_KERNEL" --timeout="$CELL_TIMEOUT" --output="$OUTPUT" "$NOTEBOOK"
echo "Done. Output written to $OUTPUT"
