configure-users

Configures the OIDC users, and the Polaris users, of a cloud.

Usage:
  afon-cli.sh configure-users <cloud>
  afon-cli.sh configure-users <cloud> <catalog>

With the cloud only, configures the OIDC users, the Jupyter browser client if the cloud has a Jupyter domain, and
the Polaris users on every catalog.  It is cloud-scoped, meant to run once per cloud and before any catalog
exists: it creates the Keycloak realm and each user's confidential client.
With a catalog name, configures the Polaris users for just that catalog, eg to pick up a catalog created after
the cloud was last configured.  Use 'all' as the catalog name to populate all the catalogs of the cloud.
