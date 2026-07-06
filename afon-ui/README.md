# afon-ui

Static configuration generator for `afon-deployment.yaml`
(see `docs/config_driven_architecture.md` §6).

**Pure config generator**: no cloud credentials, no deploy button, no backend.
The output is a YAML file you review in a pull request and deploy with the CLI.

## Validation parity

The JSON Schema is imported at build time straight from
`afon-config/src/main/resources/afon-deployment.schema.json` — the same file
the Java planner validates against — so the UI and the planner can never drift.
Cross-reference rules the schema cannot express (unique cloud ids, `via` must
name a different declared cloud, engine/workbench targets must exist) are
enforced in `src/validate.ts` and again by the planner.

## Development

```bash
npm install
npm run dev        # http://localhost:5173
npm run build      # static files in dist/ (deployable to S3/Netlify/file://)
npm run test:e2e   # Playwright: builds a three-cloud config, validates the download
```

Before first `test:e2e` run: `npx playwright install chromium`.
