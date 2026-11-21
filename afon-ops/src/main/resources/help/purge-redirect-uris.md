purge-redirect-uris

Takes the stale redirect URIs off the Jupyter browser client (OAUTH2_CLIENT_ID) of the Keycloak of a cloud.

Usage:
  afon-cli.sh purge-redirect-uris <cloud>
  afon-cli.sh purge-redirect-uris <cloud> --dry-run
  afon-cli.sh purge-redirect-uris <cloud> --keycloak-endpoint=<host:port>

A redirect URI is stale if it is not a callback (/oauth2/callback, /hub/oauth_callback) of the <CLOUD>_JUPYTER_DOMAIN_NAME
of any cloud in the config, eg that of a cloud that is no longer deployed, which OIDC_REDIRECT_STRATEGY=merge leaves on
the client.  Nothing else of the client changes.  It refuses if no cloud has a Jupyter domain name, as it would take every
URI off, and has nothing to do for a project's TRE, whose client is made by the central identity.
  --dry-run            only lists the redirect URIs that would be taken off.
  --keycloak-endpoint  reaches Keycloak there, eg of a port-forward to its pod, rather than at its OIDC domain name.
