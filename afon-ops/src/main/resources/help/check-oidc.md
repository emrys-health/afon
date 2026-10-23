check-oidc

Checks that the OIDC provider (Keycloak) of the cloud is serving requests, at its OIDC domain name, ie its
<CLOUD>_OIDC_DOMAIN_NAME if set, else OIDC_DOMAIN_NAME, eg before an OIDC notebook smoke test asks it for a token.

Usage:
  afon-cli.sh check-oidc <cloud>

Exits with 1 if the provider is not ready.
