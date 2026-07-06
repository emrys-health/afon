import { expect, test } from "@playwright/test";
import Ajv2020 from "ajv/dist/2020.js";
import { load } from "js-yaml";
import { readFileSync } from "node:fs";

// The design doc's UI gate (§7.1): build a three-cloud config through the UI
// and assert the downloaded YAML validates against the shared JSON Schema.
import schema from "../../afon-config/src/main/resources/afon-deployment.schema.json" with { type: "json" };

test("a three-cloud config built in the UI downloads as schema-valid YAML", async ({ page }) => {
  await page.goto("/");

  await page.getByLabel("name", { exact: true }).first().fill("three-cloud-demo");

  // 1. Clouds — one of each provider, with their per-provider fields.
  await page.getByTestId("add-cloud-aws").click();
  await page.locator('[name="cloud-0-storage-bucket"]').fill("demo-bucket");
  await page.locator('[name="cloud-0-storage-roleArn"]').fill("arn:aws:iam::1:role/Demo");
  await page.locator('[name="cloud-0-polaris-domain"]').fill("polaris.aws.example.com");

  await page.getByTestId("add-cloud-azure").click();
  await page.locator('[name="cloud-1-storage-storageAccount"]').fill("demoaccount");
  await page.locator('[name="cloud-1-storage-container"]').fill("democontainer");
  await page.locator('[name="cloud-1-polaris-domain"]').fill("polaris.azure.example.com");

  await page.getByTestId("add-cloud-gcp").click();
  await page.locator('[name="cloud-2-storage-bucket"]').fill("demo_gcp_bucket");
  await page.locator('[name="cloud-2-storage-serviceAccount"]').fill("sa@example.iam.gserviceaccount.com");
  await page.locator('[name="cloud-2-polaris-domain"]').fill("polaris.gcp.example.com");

  // 2. Catalogs were auto-seeded, one per cloud.
  await expect(page.locator('[name="catalog-0-name"]')).toHaveValue("aws");
  await expect(page.locator('[name="catalog-2-name"]')).toHaveValue("gcp");

  // 3. Engines — Spark on AWS.
  await page.getByTestId("toggle-engine-spark").click();

  // 4. Workbench — VS Code on AWS behind OIDC.
  await page.getByTestId("workbench-vscode").click();
  await page.locator('[name="workbench-domain"]').fill("workbench.example.com");

  // 5. Review — valid, then download.
  await expect(page.getByTestId("valid")).toBeVisible();
  const downloadPromise = page.waitForEvent("download");
  await page.getByTestId("download").click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("afon-deployment.yaml");

  const yaml = readFileSync(await download.path(), "utf-8");
  const config = load(yaml) as Record<string, unknown>;

  const validate = new Ajv2020({ allErrors: true, strict: false }).compile(schema);
  expect(validate(config), JSON.stringify(validate.errors, null, 2)).toBe(true);

  expect(config).toMatchObject({
    apiVersion: "afon/v1",
    deployment: { name: "three-cloud-demo" },
    workbench: { type: "vscode", target: "aws", domain: "workbench.example.com" },
  });
  expect((config.clouds as unknown[]).length).toBe(3);
});

test("schema violations block the download", async ({ page }) => {
  await page.goto("/");
  // No name, no clouds — the download must be disabled and errors shown.
  await expect(page.getByTestId("errors")).toBeVisible();
  await expect(page.getByTestId("download")).toBeDisabled();
});
