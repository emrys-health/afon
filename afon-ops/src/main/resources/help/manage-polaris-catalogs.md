manage-polaris-catalogs

Creates, deletes or recreates the Polaris catalogs, namespaces and tables of a cloud.

Usage:
  afon-cli.sh manage-polaris-catalogs <delete|create|recreate> <cloud>
  afon-cli.sh manage-polaris-catalogs <delete|create> <cloud> <catalog>
  afon-cli.sh manage-polaris-catalogs delete <cloud> <catalog> [<namespace> [<table>]]

<catalog> is the id of the catalog, as configured in <CLOUD>_CATALOG_IDS, for creating and deleting alike.

Creating leaves a catalog that exists as it is, with its roles and tables, setting only its admin grants; to
rebuild the catalogs, use recreate, which deletes them all first.

Deleting deletes just what is named: every catalog if none is, else the catalog if no namespace is named, else the
namespace if no table is named, else the table.  Deleting a namespace or table leaves the catalog, and deleting a
table leaves the namespace.  Deleting all the catalogs excludes glue.  Deleting a catalog deletes its roles,
other than catalog_admin, first, as Polaris does not drop a catalog that has them.  An external catalog is only a
federation to a catalog of another Polaris, so its namespaces and tables are left alone.
