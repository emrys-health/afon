# Generate synthetic EHR

Runs in the common python env at scripts/python (see `python/setup.sh`), which includes fastparquet.

Generate and import into the env of a site, with either of:

    ./afon.sh -a data-synthetic-ehr --site <site>
    afon-cli.sh data-synthetic-ehr --site <site>

The parquet files are written to `$AFON_ROOT/target/synthetic-ehr/<site>` by default (see `--output`).

To only generate, in the common python env (with `AFON_ROOT` set to the afon root):

    source $AFON_ROOT/scripts/python/python-source.sh
    python scripts/data/generate_synthetic_ehr.py --patients 800 --site <site> --seed 1 --output target/<site>   

