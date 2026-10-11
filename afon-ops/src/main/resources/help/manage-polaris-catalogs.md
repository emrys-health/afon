manage-polaris-catalogs

Creates, deletes or recreates the Polaris catalogs, namespaces and tables of a cloud.

Usage:
  afon-cli.sh manage-polaris-catalogs <delete|create|recreate> <cloud>
  afon-cli.sh manage-polaris-catalogs <delete|create> <cloud> <catalog>
  afon-cli.sh manage-polaris-catalogs delete <cloud> <catalog> [<namespace> [<table>]]

Deleting deletes just what is named: every catalog if none is, else the catalog if no namespace is named, else the
namespace if no table is named, else the table.  Deleting a namespace or table leaves the catalog, and deleting a
table leaves the namespace.  Deleting all the catalogs excludes glue.
