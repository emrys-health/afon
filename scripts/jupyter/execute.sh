source $AFON_ROOT/scripts/python/python-source.sh

NOTEBOOK="${1:-}"
OUTPUT="${2:-$NOTEBOOK}"

if [ -z "$NOTEBOOK" ]; then
  echo "Action jupyter-execute requires a notebook path, eg: ./afon.sh -a jupyter-execute -sa <notebook.ipynb>"
  exit 0
fi
if [ ! -f "$NOTEBOOK" ]; then
  echo "Notebook not found: $NOTEBOOK"
  exit 1
fi

echo "Executing $NOTEBOOK (kernel '$PYTHON_KERNEL')..."
jupyter execute --kernel_name="$PYTHON_KERNEL" --output="$OUTPUT" "$NOTEBOOK"
echo "Done. Output written to $OUTPUT"
