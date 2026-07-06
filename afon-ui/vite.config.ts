import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The JSON Schema is imported straight from afon-config so the UI and the
// Java planner can never drift (docs/config_driven_architecture.md §6.3).
// fs.allow lets the dev server read it from outside this module's root.
export default defineConfig({
  plugins: [react()],
  server: {
    fs: { allow: [".."] },
  },
});
