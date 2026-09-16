# Generate synthetic EHR

brew install uv
uv venv
source .venv/bin/activate

site in "westmids wessex tvs"
python generate_synthetic_ehr.py --patients 800 --site <site> --seed 1 --output target/<site>