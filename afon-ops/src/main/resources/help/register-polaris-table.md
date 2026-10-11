register-polaris-table

Creates the namespace and registers the table, from its metadata file, in a Polaris catalog.

Usage:
  afon-cli.sh register-polaris-table <cloud> <catalog> <namespace> <table> <metadata> [<polaris-id> <polaris-secret>]

<metadata> is the location of the table's metadata json, eg gs://bucket/path/metadata/00000-<uuid>.metadata.json.
Without <polaris-id> and <polaris-secret>, the configured POLARIS_ID and POLARIS_SECRET are used.
