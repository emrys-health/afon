check-oidc

Checks that the OIDC provider (Keycloak) at OIDC_DOMAIN_NAME is serving requests, eg before an OIDC notebook
smoke test asks it for a token.

Usage:
  afon-cli.sh check-oidc

Exits with 1 if the provider is not ready.
