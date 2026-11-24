# KeyCloak

## The browser client of Jupyter

Logins to the Jupyter of a cloud go through one browser client of the `polaris` realm, `OAUTH2_CLIENT_ID`, whose redirect URIs are the callbacks of that
cloud's Jupyter (`<CLOUD>_JUPYTER_DOMAIN_NAME`): `https://auth.<jupyter>/oauth2/callback` (Pomerium), `https://auth.<jupyter>/.pomerium/signed_out` (Pomerium post-logout), and `https://<jupyter>/hub/oauth_callback` (JupyterHub), with `post.logout.redirect.uris` set to `+`.  The setup of a stack that deploys Jupyter, eg `aws-full`, registers it (step
`configure_jupyter_client_for_cloud_<cloud>`), overwriting the redirect URIs of a client that is already there.  The stacks that do not deploy Jupyter,
eg the Polaris-only and KeyCloak-only ones, do not register it, so cannot overwrite it, and a setup that cannot create or update it fails.

Clouds whose KeyCloaks have the same database have the same realm, so the same client: two of them with a Jupyter each, eg in a test env that sets up
several clouds at once, cannot both be logged in to, as the one set up last wins, and the other gets KeyCloak's `Invalid parameter: redirect_uri`.  The
setup warns of the redirect URIs that it overwrites.  A project's TRE does not share a client: its own is made by the central identity.

To have such clouds each keep their Jupyter's callbacks, set `OIDC_REDIRECT_STRATEGY=merge` in `afon.properties`: the setup then adds the redirect URIs of its
Jupyter to those of the client that is already there.  The default, `replace` (also when it is empty), replaces them, so that the client of a production env holds
only what is deployed; any other value fails the setup.  With `merge`, the redirect URIs of a cloud that is no longer deployed stay.  It is meant for envs that set up
several clouds against one KeyCloak, eg the smoke tests.  `afon-cli.sh purge-redirect-uris <cloud> [--dry-run]` then takes the stale ones off the client: those that are not the callbacks of the
Jupyter domain, `<CLOUD>_JUPYTER_DOMAIN_NAME`, of any cloud of the config.

## Database Bootstrap

Checkout KeyCloak from Github.

Run:
```shell
mvn clean install -DskipTests -Dgpg.skip=true
```
Find the executible with:
```shell
find . -name kc.sh
```
For me, it was at:
```
./testsuite/integration-arquillian/servers/auth-server/quarkus/target/unpacked/keycloak-999.0.0-SNAPSHOT/bin/kc.sh
```
Edit `./quarkus/dist/src/main/content/conf/keycloak.conf` and put your properties in there.

Note that you will need to create the actual database in your RDBMS by hand.

Now you have everything so run:
```shell
./testsuite/integration-arquillian/servers/auth-server/quarkus/target/unpacked/keycloak-999.0.0-SNAPSHOT/bin/kc.sh -cf ./quarkus/dist/src/main/content/conf/keycloak.conf start-dev
```
and it will start a server locally but it will bootstrap the database.