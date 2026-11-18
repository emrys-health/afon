#!/usr/bin/env bash
# Sourced by the scripts that make or delete what the catalogs of the stack's own Polaris need, eg their buckets, which loop over <CLOUD>_CATALOG_IDS.
# A catalog says where it is served from with <CLOUD>_CATALOG_SOURCE_<id>: unset or "stack" for the stack's own Polaris, whose storage these scripts make
# and delete, or "data-provider" for a catalog of a Data Provider that a TRE project reads, whose storage is the Data Provider's, and which they must
# never make or delete.  A config outside a project has only the first.

# catalog_is_own <CLOUD> <id>: succeeds if the catalog is served from the stack's own Polaris
catalog_is_own() {
  local var="${1}_CATALOG_SOURCE_${2}"
  [ -z "${!var:-}" ] || [ "${!var}" = "stack" ]
}

# own_catalog_ids <CLOUD> <array name>: leaves in the array only the ids of the catalogs of the stack's own Polaris, in their order
own_catalog_ids() {
  local cloud="$1" id
  local -n ids_ref="$2"
  local own=()
  for id in ${ids_ref[@]+"${ids_ref[@]}"}; do
    if catalog_is_own "$cloud" "$id"; then
      own+=("$id")
    fi
  done
  ids_ref=(${own[@]+"${own[@]}"})
}
