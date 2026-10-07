# Theming

Guacamole's UI and KeyCloak's login page (plus the KeyCloak realm's display name) are branded from a
single place: the `afon-theme` Maven module. Each deployment picks one theme via the `AFON_THEME`
property; adding a new theme is purely a matter of adding files, no code changes.

## Selecting a theme

Set `AFON_THEME` in `afon.properties` (see [configuration](./configuration.md)):

```properties
AFON_THEME=emrys-hdrs
```

It defaults to `emrys-hdrs` if unset.

## Layout

Every theme lives under `afon-theme/src/main/resources/themes/<name>/`:

```
themes/<name>/
  guacamole/              # Guacamole extension — becomes a standalone jar at deploy time
    guac-manifest.json
    css/...
    images/...
    translations/...
  keycloak/login/         # KeyCloak login theme, mounted at /opt/keycloak/themes/<name>
    theme.properties
    resources/css/...
    resources/img/...
  realm.properties         # optional realm branding: displayName, displayNameHtml
```

Only `guacamole/guac-manifest.json` and `keycloak/login/theme.properties` are required — everything
else is whatever files that theme needs; nothing is hardcoded by name or count. `realm.properties` is
optional too: if a theme doesn't define `displayName`/`displayNameHtml`, KeyCloak just falls back to
showing the realm id.

## Adding a new theme

1. Create `afon-theme/src/main/resources/themes/<name>/guacamole/`, following
   [Guacamole's branding extension format](https://guacamole.apache.org/doc/gug/branding-guacamole.html)
   (a `guac-manifest.json` plus whatever css/images/translations it references).
2. Create `afon-theme/src/main/resources/themes/<name>/keycloak/login/`, following
   [KeyCloak's theme format](https://www.keycloak.org/docs/latest/server_development/#_themes) for the
   `login` type (a `theme.properties` plus whatever resources it references).
3. Optionally add `afon-theme/src/main/resources/themes/<name>/realm.properties` with `displayName`
   and/or `displayNameHtml`.
4. Set `AFON_THEME=<name>` and deploy.

No changes to `GuacamoleConfig`, `KeyCloakConfig`, or `KeyCloakProvider` are needed.

## How it's wired

- `ThemeResources` (`afon-cdk`) reads every file under a theme's `guacamole/` or `keycloak/login/`
  subtree off the synth classpath, whether `afon-theme` is unpacked (IDE, tests) or packaged in a jar
  — including inside `afon-cli.jar`'s `BOOT-INF/lib`.
- `GuacamoleConfig` re-zips the `guacamole/` subtree into a standalone extension jar, shipped as a
  ConfigMap `binaryData` entry and copied into `GUACAMOLE_HOME/extensions` at pod start.
- `KeyCloakConfig` ships the `keycloak/login/` subtree as a ConfigMap, one entry per file (flattened
  into a valid ConfigMap key, reshaped back into the real path via the volume's `items`), mounted at
  `/opt/keycloak/themes/<name>`. Files that decode as valid UTF-8 (css, svg, `theme.properties`, ...)
  ride in `data` so they stay readable via `kubectl`; anything else (images, fonts, ...) rides in
  `binaryData`.
- `KeyCloakProvider#createRealm` reads `realm.properties` (if present) to set the realm's
  `displayName`/`displayNameHtml`, and sets `loginTheme` to the active theme name.

## Verifying a theme without deploying

`cdktf synth` (via `afon-cli.sh validate-stack <stack>`, e.g. `aws-full`) generates the
Terraform plan locally with no cloud credentials or live calls — useful to check a new theme's
ConfigMaps and volume mounts come out correctly before an actual deploy.
