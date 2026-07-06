import { useMemo, useState } from "react";
import { Download, FileWarning, CheckCircle2, Settings2 } from "lucide-react";
import type { DeploymentDraft } from "./model";
import { emptyDraft } from "./model";
import { buildConfig, toYaml } from "./config";
import { crossReferenceErrors, schemaErrors } from "./validate";
import {
  CatalogsSection,
  CloudsSection,
  EnginesSection,
  Field,
  Section,
  WorkbenchSection,
} from "./sections";

export default function App() {
  const [draft, setDraft] = useState<DeploymentDraft>(emptyDraft);

  // Immer-style updates without the dependency: clone, mutate, set.
  const update = (mutate: (d: DeploymentDraft) => void) =>
    setDraft((prev) => {
      const next = structuredClone(prev);
      mutate(next);
      return next;
    });

  const config = useMemo(() => buildConfig(draft), [draft]);
  const yaml = useMemo(() => toYaml(config), [config]);
  const errors = useMemo(
    () => [...schemaErrors(config), ...crossReferenceErrors(draft)],
    [config, draft]
  );

  const download = () => {
    const blob = new Blob([yaml], { type: "application/yaml" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "afon-deployment.yaml";
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <div className="mx-auto max-w-7xl space-y-6 p-6">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">
            Afon <span className="text-sky-400">deployment configuration</span>
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            Pure config generator — no credentials, no deploy button. Download the YAML,
            review it in a pull request, deploy with the CLI.
          </p>
        </div>
      </header>

      <div className="grid gap-6 xl:grid-cols-[1fr_28rem]">
        <div className="space-y-6">
          <Section step={0} title="Deployment" icon={<Settings2 size={18} className="text-slate-400" />}>
            <div className="grid grid-cols-3 gap-3">
              <Field
                label="name"
                name="deployment-name"
                value={draft.name}
                placeholder="emrys-demo"
                onChange={(v) => update((d) => (d.name = v))}
              />
              <Field
                label="clusterName"
                name="deployment-clusterName"
                value={draft.clusterName}
                placeholder="spark-cluster"
                onChange={(v) => update((d) => (d.clusterName = v))}
              />
              <Field
                label="secretsFile"
                name="deployment-secretsFile"
                value={draft.secretsFile}
                onChange={(v) => update((d) => (d.secretsFile = v))}
              />
            </div>
          </Section>
          <CloudsSection draft={draft} update={update} />
          <CatalogsSection draft={draft} update={update} />
          <EnginesSection draft={draft} update={update} />
          <WorkbenchSection draft={draft} update={update} />
        </div>

        {/* 5. Review — live YAML preview, inline errors, download (§6.2.5) */}
        <aside className="card sticky top-6 flex h-fit max-h-[calc(100vh-3rem)] flex-col gap-3">
          <div className="flex items-center justify-between">
            <h2 className="section-title">
              <span className="step-badge">5</span> Review
            </h2>
            <button
              className="btn-primary"
              data-testid="download"
              disabled={errors.length > 0}
              onClick={download}
            >
              <Download size={14} /> Download afon-deployment.yaml
            </button>
          </div>

          {errors.length > 0 ? (
            <div className="space-y-1 rounded-md border border-rose-900 bg-rose-950/40 p-3" data-testid="errors">
              {errors.map((e, i) => (
                <p key={i} className="flex items-start gap-1.5 text-xs text-rose-300">
                  <FileWarning size={13} className="mt-0.5 shrink-0" /> {e}
                </p>
              ))}
            </div>
          ) : (
            <p className="flex items-center gap-1.5 text-xs text-emerald-400" data-testid="valid">
              <CheckCircle2 size={13} /> Valid against afon-deployment.schema.json
            </p>
          )}

          <pre
            className="flex-1 overflow-auto rounded-md border border-slate-800 bg-slate-950 p-3 font-mono text-xs leading-relaxed text-slate-300"
            data-testid="yaml-preview"
          >
            {yaml}
          </pre>
        </aside>
      </div>
    </div>
  );
}
