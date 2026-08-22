import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const assetRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "..",
  "app",
  "src",
  "main",
  "assets",
  "web",
);
const indexHtml = readFileSync(path.join(assetRoot, "index.html"), "utf8");

test("local-network advisory never covers expanded projection video", () => {
  assert.match(
    indexHtml,
    /#viewer:fullscreen #local-network-panel,\s*body\.theater-mode #local-network-panel \{\s*display: none !important;\s*\}/u,
  );
  assert.match(
    indexHtml,
    /#viewer:fullscreen #media-permission-panel,\s*body\.theater-mode #media-permission-panel \{\s*position: absolute;/u,
  );
});
