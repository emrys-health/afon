check-oidc

Checks that the OIDC provider (Keycloak) of the cloud is serving requests, at its OIDC domain name, ie its
<CLOUD>_OIDC_DOMAIN_NAME if set, else OIDC_DOMAIN_NAME, eg before an OIDC notebook smoke test asks it for a token.

Usage:
  afon-cli.sh check-oidc <cloud>
  afon-cli.sh check-oidc <cloud> --token
  afon-cli.sh check-oidc <cloud> --token user
  afon-cli.sh check-oidc <cloud> --token <username>

Exits with 1 if the provider is not ready.
With --token, once the provider is ready, also shows an OIDC access token, as JupyterHub injects into a user's pod as
OIDC_TOKEN, eg for the OIDC notebook smoke test:
  --token       the token of the POLARIS_ID admin client, using POLARIS_SECRET.
  --token user  the token of the USER_ID user, using its password from users.csv.
  --token <username>  the token of that user from users.csv.
