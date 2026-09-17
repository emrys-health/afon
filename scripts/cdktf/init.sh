INIT_DIR=
if [ -n "$CDKTF_ENV" ]; then
  INIT_DIR="$CDKTF_DIR-$CDKTF_ENV"
else
  INIT_DIR="$CDKTF_DIR"
fi
mkdir -p $INIT_DIR
echo "Created cdktf dir $INIT_DIR"

if [ -n "$CDKTF_ENV" ]; then
  echo "The cdktf dir for env '$CDKTF_ENV' has been created."
  echo "To switch to env '$CDKTF_ENV':"
  echo "./afon.sh -a cdktf-switch --cdktf-env $CDKTF_ENV"
fi
