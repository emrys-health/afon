show-oidc-token

Shows an OIDC access token from the identity provider, as JupyterHub injects into a user's pod as OIDC_TOKEN,
eg for the OIDC notebook smoke test.

Usage:
  afon-cli.sh show-oidc-token <cloud>
  afon-cli.sh show-oidc-token <cloud> user

With the cloud only (whose Keycloak is at its OIDC domain name, see check-oidc), gets the token of the POLARIS_ID admin client, using POLARIS_SECRET.
With 'user', gets the token of the USER_ID user, using its password from users.csv.
