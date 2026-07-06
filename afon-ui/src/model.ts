// Mirrors afon-config's afon-deployment.schema.json (the single source of
// truth — imported directly, see validate.ts). Secrets never appear here:
// fields ending in `Ref` name properties in the secrets file.

export type Provider = "aws" | "azure" | "gcp";
export type EngineType = "spark" | "trino" | "snowflake";
export type WorkbenchType = "jupyter" | "vscode" | "rstudio";
export type CatalogType = "internal" | "federated";

export interface Cloud {
  id: string;
  provider: Provider;
  region: string;
  storage: Record<string, string>;
  polaris: Record<string, string>;
}

export interface Catalog {
  name: string;
  cloud: string;
  type: CatalogType;
  via?: string;
  bucketOverride?: string;
  testFixture?: boolean;
}

export interface Engine {
  type: EngineType;
  target?: string;
  workers?: number;
  jdbcUrlRef?: string;
  readsCatalogs?: string[];
}

export interface Auth {
  provider: string;
  emailDomain?: string;
  clientIdRef?: string;
  clientSecretRef?: string;
  cookieSecretRef?: string;
}

export interface Workbench {
  type: WorkbenchType;
  target: string;
  domain: string;
  auth?: Auth;
}

export interface DeploymentDraft {
  name: string;
  clusterName: string;
  clouds: Cloud[];
  catalogs: Catalog[];
  engines: Engine[];
  workbench: Workbench | null;
  secretsFile: string;
}

/** Provider-specific form fields (schema keeps storage/polaris free-form). */
export const STORAGE_FIELDS: Record<Provider, string[]> = {
  aws: ["bucket", "roleArn"],
  azure: ["storageAccount", "container"],
  gcp: ["bucket", "serviceAccount"],
};

export const POLARIS_FIELDS: Record<Provider, string[]> = {
  aws: ["domain", "certArn", "dnsZone"],
  azure: ["domain", "resourceGroup", "subscription"],
  gcp: ["domain"],
};

export const DEFAULT_REGIONS: Record<Provider, string> = {
  aws: "eu-west-2",
  azure: "uksouth",
  gcp: "europe-west2",
};

export function emptyCloud(provider: Provider, existing: Cloud[]): Cloud {
  const taken = new Set(existing.map((c) => c.id));
  let id: string = provider;
  for (let i = 2; taken.has(id); i++) id = `${provider}-${i}`;
  return { id, provider, region: DEFAULT_REGIONS[provider], storage: {}, polaris: {} };
}

/** §6.2: catalogs are auto-seeded with one internal catalog per cloud. */
export function seededCatalog(cloud: Cloud): Catalog {
  return { name: cloud.id.replace(/-/g, "_"), cloud: cloud.id, type: "internal" };
}

export function emptyDraft(): DeploymentDraft {
  return {
    name: "",
    clusterName: "",
    clouds: [],
    catalogs: [],
    engines: [],
    workbench: null,
    secretsFile: "env.properties",
  };
}
