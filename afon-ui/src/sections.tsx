import type { ReactNode } from "react";
import { Cloud as CloudIcon, Database, Plus, Trash2, Cpu, Laptop } from "lucide-react";
import type {
  Catalog,
  Cloud,
  DeploymentDraft,
  Engine,
  EngineType,
  Provider,
  Workbench,
  WorkbenchType,
} from "./model";
import { POLARIS_FIELDS, STORAGE_FIELDS, emptyCloud, seededCatalog } from "./model";

type Update = (mutate: (draft: DeploymentDraft) => void) => void;

export function Section(props: {
  step: number;
  title: string;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="card space-y-4" data-section={props.title.toLowerCase()}>
      <h2 className="section-title">
        <span className="step-badge">{props.step}</span>
        {props.icon}
        {props.title}
      </h2>
      {props.children}
    </section>
  );
}

export function Field(props: {
  label: string;
  children?: ReactNode;
  value?: string;
  placeholder?: string;
  name?: string;
  onChange?: (v: string) => void;
}) {
  return (
    <label className="block">
      <span className="label">{props.label}</span>
      {props.children ?? (
        <input
          className="input"
          name={props.name}
          value={props.value ?? ""}
          placeholder={props.placeholder}
          onChange={(e) => props.onChange?.(e.target.value)}
        />
      )}
    </label>
  );
}

// ---------------------------------------------------------------------------
// 1. Clouds — repeatable card, storage fields switch per provider (§6.2.1)
// ---------------------------------------------------------------------------

export function CloudsSection({ draft, update }: { draft: DeploymentDraft; update: Update }) {
  const addCloud = (provider: Provider) =>
    update((d) => {
      const cloud = emptyCloud(provider, d.clouds);
      d.clouds.push(cloud);
      d.catalogs.push(seededCatalog(cloud)); // auto-seed (§6.2.2)
    });

  return (
    <Section step={1} title="Clouds" icon={<CloudIcon size={18} className="text-sky-400" />}>
      <div className="flex gap-2">
        {(["aws", "azure", "gcp"] as Provider[]).map((p) => (
          <button key={p} className="btn-ghost" data-testid={`add-cloud-${p}`} onClick={() => addCloud(p)}>
            <Plus size={14} /> Add {p}
          </button>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        {draft.clouds.map((cloud, i) => (
          <CloudCard key={i} index={i} cloud={cloud} update={update} />
        ))}
      </div>
      {draft.clouds.length === 0 && (
        <p className="text-sm text-slate-500">At least one cloud is required.</p>
      )}
    </Section>
  );
}

function CloudCard({ index, cloud, update }: { index: number; cloud: Cloud; update: Update }) {
  const set = (mutate: (c: Cloud) => void) => update((d) => mutate(d.clouds[index]));
  return (
    <div className="space-y-3 rounded-lg border border-slate-800 bg-slate-950/60 p-4" data-testid={`cloud-${index}`}>
      <div className="flex items-center justify-between">
        <span className="rounded bg-slate-800 px-2 py-0.5 font-mono text-xs uppercase text-sky-300">
          {cloud.provider}
        </span>
        <button
          className="btn-danger"
          aria-label={`remove cloud ${cloud.id}`}
          onClick={() =>
            update((d) => {
              d.catalogs = d.catalogs.filter((c) => c.cloud !== cloud.id && c.via !== cloud.id);
              d.clouds.splice(index, 1);
            })
          }
        >
          <Trash2 size={14} />
        </button>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label="id" name={`cloud-${index}-id`} value={cloud.id} onChange={(v) => set((c) => (c.id = v))} />
        <Field
          label="region"
          name={`cloud-${index}-region`}
          value={cloud.region}
          onChange={(v) => set((c) => (c.region = v))}
        />
        {STORAGE_FIELDS[cloud.provider].map((f) => (
          <Field
            key={f}
            label={`storage.${f}`}
            name={`cloud-${index}-storage-${f}`}
            value={cloud.storage[f] ?? ""}
            onChange={(v) => set((c) => (c.storage[f] = v))}
          />
        ))}
        {POLARIS_FIELDS[cloud.provider].map((f) => (
          <Field
            key={f}
            label={`polaris.${f}`}
            name={`cloud-${index}-polaris-${f}`}
            value={cloud.polaris[f] ?? ""}
            onChange={(v) => set((c) => (c.polaris[f] = v))}
          />
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// 2. Catalogs — auto-seeded, user can add federated + test fixtures (§6.2.2)
// ---------------------------------------------------------------------------

export function CatalogsSection({ draft, update }: { draft: DeploymentDraft; update: Update }) {
  const cloudIds = draft.clouds.map((c) => c.id);
  return (
    <Section step={2} title="Catalogs" icon={<Database size={18} className="text-emerald-400" />}>
      <button
        className="btn-ghost"
        data-testid="add-catalog"
        disabled={cloudIds.length === 0}
        onClick={() =>
          update((d) =>
            d.catalogs.push({ name: "", cloud: cloudIds[0], type: "internal" })
          )
        }
      >
        <Plus size={14} /> Add catalog
      </button>
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-slate-500">
          <tr>
            <th className="py-1 pr-2">name</th>
            <th className="pr-2">cloud</th>
            <th className="pr-2">type</th>
            <th className="pr-2">via</th>
            <th className="pr-2">bucket override</th>
            <th className="pr-2">fixture</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {draft.catalogs.map((cat, i) => (
            <CatalogRow key={i} index={i} cat={cat} cloudIds={cloudIds} update={update} />
          ))}
        </tbody>
      </table>
    </Section>
  );
}

function CatalogRow({
  index,
  cat,
  cloudIds,
  update,
}: {
  index: number;
  cat: Catalog;
  cloudIds: string[];
  update: Update;
}) {
  const set = (mutate: (c: Catalog) => void) => update((d) => mutate(d.catalogs[index]));
  return (
    <tr className="border-t border-slate-800">
      <td className="py-1.5 pr-2">
        <input
          className="input"
          name={`catalog-${index}-name`}
          value={cat.name}
          onChange={(e) => set((c) => (c.name = e.target.value))}
        />
      </td>
      <td className="pr-2">
        <select className="input" value={cat.cloud} onChange={(e) => set((c) => (c.cloud = e.target.value))}>
          {cloudIds.map((id) => (
            <option key={id}>{id}</option>
          ))}
        </select>
      </td>
      <td className="pr-2">
        <select
          className="input"
          value={cat.type}
          onChange={(e) =>
            set((c) => {
              c.type = e.target.value as Catalog["type"];
              if (c.type === "internal") delete c.via;
            })
          }
        >
          <option>internal</option>
          <option>federated</option>
        </select>
      </td>
      <td className="pr-2">
        {cat.type === "federated" ? (
          <select className="input" value={cat.via ?? ""} onChange={(e) => set((c) => (c.via = e.target.value))}>
            <option value="">—</option>
            {cloudIds
              .filter((id) => id !== cat.cloud)
              .map((id) => (
                <option key={id}>{id}</option>
              ))}
          </select>
        ) : (
          <span className="text-slate-600">—</span>
        )}
      </td>
      <td className="pr-2">
        <input
          className="input"
          value={cat.bucketOverride ?? ""}
          onChange={(e) => set((c) => (c.bucketOverride = e.target.value))}
        />
      </td>
      <td className="pr-2 text-center">
        <input
          type="checkbox"
          checked={cat.testFixture ?? false}
          onChange={(e) => set((c) => (c.testFixture = e.target.checked))}
        />
      </td>
      <td>
        <button
          className="btn-danger"
          aria-label={`remove catalog ${cat.name}`}
          onClick={() => update((d) => d.catalogs.splice(index, 1))}
        >
          <Trash2 size={14} />
        </button>
      </td>
    </tr>
  );
}

// ---------------------------------------------------------------------------
// 3. Query engines — multi-select cards (§6.2.3)
// ---------------------------------------------------------------------------

export function EnginesSection({ draft, update }: { draft: DeploymentDraft; update: Update }) {
  const cloudIds = draft.clouds.map((c) => c.id);
  const has = (t: EngineType) => draft.engines.some((e) => e.type === t);
  const toggle = (t: EngineType) =>
    update((d) => {
      if (d.engines.some((e) => e.type === t)) {
        d.engines = d.engines.filter((e) => e.type !== t);
      } else {
        d.engines.push(
          t === "snowflake"
            ? { type: t, jdbcUrlRef: "SNOWFLAKE_JDBC_URL", readsCatalogs: [] }
            : { type: t, target: cloudIds[0] ?? "", workers: 2 }
        );
      }
    });

  return (
    <Section step={3} title="Query engines" icon={<Cpu size={18} className="text-amber-400" />}>
      <div className="flex gap-2">
        {(["spark", "trino", "snowflake"] as EngineType[]).map((t) => (
          <button
            key={t}
            data-testid={`toggle-engine-${t}`}
            className={has(t) ? "btn-primary" : "btn-ghost"}
            onClick={() => toggle(t)}
          >
            {t}
          </button>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        {draft.engines.map((engine, i) => (
          <EngineCard key={engine.type} index={i} engine={engine} draft={draft} update={update} />
        ))}
      </div>
    </Section>
  );
}

function EngineCard({
  index,
  engine,
  draft,
  update,
}: {
  index: number;
  engine: Engine;
  draft: DeploymentDraft;
  update: Update;
}) {
  const set = (mutate: (e: Engine) => void) => update((d) => mutate(d.engines[index]));
  const cloudIds = draft.clouds.map((c) => c.id);
  return (
    <div className="space-y-3 rounded-lg border border-slate-800 bg-slate-950/60 p-4">
      <span className="font-mono text-sm text-amber-300">{engine.type}</span>
      {engine.type === "snowflake" ? (
        <>
          <Field
            label="jdbcUrlRef"
            name={`engine-${index}-jdbcUrlRef`}
            value={engine.jdbcUrlRef ?? ""}
            onChange={(v) => set((e) => (e.jdbcUrlRef = v))}
          />
          <Field label="reads catalogs">
            <div className="flex flex-wrap gap-1.5">
              {draft.catalogs.map((c) => {
                const selected = engine.readsCatalogs?.includes(c.name) ?? false;
                return (
                  <button
                    key={c.name}
                    className={`rounded px-2 py-0.5 text-xs ${
                      selected ? "bg-amber-600 text-white" : "bg-slate-800 text-slate-400"
                    }`}
                    onClick={() =>
                      set((e) => {
                        const list = e.readsCatalogs ?? [];
                        e.readsCatalogs = selected
                          ? list.filter((n) => n !== c.name)
                          : [...list, c.name];
                      })
                    }
                  >
                    {c.name || "(unnamed)"}
                  </button>
                );
              })}
            </div>
          </Field>
        </>
      ) : (
        <>
          <Field label="target">
            <select
              className="input"
              value={engine.target ?? ""}
              onChange={(e) => set((en) => (en.target = e.target.value))}
            >
              {cloudIds.map((id) => (
                <option key={id}>{id}</option>
              ))}
            </select>
          </Field>
          <Field label="workers">
            <input
              className="input"
              type="number"
              min={1}
              value={engine.workers ?? 1}
              onChange={(e) => set((en) => (en.workers = parseInt(e.target.value, 10) || 1))}
            />
          </Field>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// 4. Workbench — radio incl. none, OIDC settings (§6.2.4)
// ---------------------------------------------------------------------------

export function WorkbenchSection({ draft, update }: { draft: DeploymentDraft; update: Update }) {
  const cloudIds = draft.clouds.map((c) => c.id);
  const select = (t: WorkbenchType | null) =>
    update((d) => {
      if (t === null) {
        d.workbench = null;
      } else {
        d.workbench = {
          type: t,
          target: d.workbench?.target ?? cloudIds[0] ?? "",
          domain: d.workbench?.domain ?? "",
          auth: d.workbench?.auth ?? {
            provider: "google-oidc",
            emailDomain: "*",
            clientIdRef: "OAUTH2_CLIENT_ID",
            clientSecretRef: "OAUTH2_CLIENT_SECRET",
            cookieSecretRef: "OAUTH2_COOKIE_SECRET",
          },
        };
      }
    });
  const wb = draft.workbench;

  return (
    <Section step={4} title="Workbench" icon={<Laptop size={18} className="text-violet-400" />}>
      <div className="flex gap-2">
        {([null, "jupyter", "vscode", "rstudio"] as (WorkbenchType | null)[]).map((t) => (
          <button
            key={t ?? "none"}
            data-testid={`workbench-${t ?? "none"}`}
            className={(wb?.type ?? null) === t ? "btn-primary" : "btn-ghost"}
            onClick={() => select(t)}
          >
            {t ?? "none"}
          </button>
        ))}
      </div>
      {wb && <WorkbenchForm wb={wb} cloudIds={cloudIds} update={update} />}
    </Section>
  );
}

function WorkbenchForm({
  wb,
  cloudIds,
  update,
}: {
  wb: Workbench;
  cloudIds: string[];
  update: Update;
}) {
  const set = (mutate: (w: Workbench) => void) => update((d) => d.workbench && mutate(d.workbench));
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
      <Field label="target">
        <select className="input" value={wb.target} onChange={(e) => set((w) => (w.target = e.target.value))}>
          {cloudIds.map((id) => (
            <option key={id}>{id}</option>
          ))}
        </select>
      </Field>
      <Field label="domain" name="workbench-domain" value={wb.domain} onChange={(v) => set((w) => (w.domain = v))} />
      <Field
        label="auth.provider"
        name="workbench-auth-provider"
        value={wb.auth?.provider ?? ""}
        onChange={(v) => set((w) => (w.auth = { ...(w.auth ?? { provider: "" }), provider: v }))}
      />
      <Field
        label="auth.emailDomain"
        value={wb.auth?.emailDomain ?? ""}
        onChange={(v) => set((w) => w.auth && (w.auth.emailDomain = v))}
      />
      <Field
        label="auth.clientIdRef"
        value={wb.auth?.clientIdRef ?? ""}
        onChange={(v) => set((w) => w.auth && (w.auth.clientIdRef = v))}
      />
      <Field
        label="auth.clientSecretRef"
        value={wb.auth?.clientSecretRef ?? ""}
        onChange={(v) => set((w) => w.auth && (w.auth.clientSecretRef = v))}
      />
      <Field
        label="auth.cookieSecretRef"
        value={wb.auth?.cookieSecretRef ?? ""}
        onChange={(v) => set((w) => w.auth && (w.auth.cookieSecretRef = v))}
      />
    </div>
  );
}
