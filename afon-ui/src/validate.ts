import Ajv2020 from "ajv/dist/2020";
// The single source of truth, shared with the Java planner
// (docs/config_driven_architecture.md §6.3): never copy this file.
import schema from "../../afon-config/src/main/resources/afon-deployment.schema.json";
import type { DeploymentDraft } from "./model";

const ajv = new Ajv2020({ allErrors: true, strict: false });
const validator = ajv.compile(schema);

/** Schema errors, phrased for humans. */
export function schemaErrors(config: Record<string, unknown>): string[] {
  if (validator(config)) return [];
  return (validator.errors ?? []).map(
    (e) => `${e.instancePath || "(root)"} ${e.message ?? "is invalid"}`
  );
}

/**
 * Cross-reference rules the schema cannot express (§4.3), enforced here in
 * the UI and again by the Java planner.
 */
export function crossReferenceErrors(draft: DeploymentDraft): string[] {
  const errors: string[] = [];
  const cloudIds = draft.clouds.map((c) => c.id);
  const seen = new Set<string>();
  for (const id of cloudIds) {
    if (seen.has(id)) errors.push(`clouds: duplicate id '${id}'`);
    seen.add(id);
  }
  const known = new Set(cloudIds);
  for (const cat of draft.catalogs) {
    if (!known.has(cat.cloud))
      errors.push(`catalog '${cat.name}': unknown cloud '${cat.cloud}'`);
    if (cat.type === "federated") {
      if (!cat.via || !known.has(cat.via))
        errors.push(`catalog '${cat.name}': federated catalogs need a declared 'via' cloud`);
      else if (cat.via === cat.cloud)
        errors.push(`catalog '${cat.name}': 'via' must be a different cloud`);
    }
  }
  for (const e of draft.engines) {
    if (e.type !== "snowflake" && e.target && !known.has(e.target))
      errors.push(`${e.type} engine: unknown target cloud '${e.target}'`);
    if (e.type === "snowflake") {
      const catalogNames = new Set(draft.catalogs.map((c) => c.name));
      for (const c of e.readsCatalogs ?? [])
        if (!catalogNames.has(c))
          errors.push(`snowflake engine: unknown catalog '${c}'`);
    }
  }
  if (draft.workbench && !known.has(draft.workbench.target))
    errors.push(`workbench: unknown target cloud '${draft.workbench.target}'`);
  return errors;
}
