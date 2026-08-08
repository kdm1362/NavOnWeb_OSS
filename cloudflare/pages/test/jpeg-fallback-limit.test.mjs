import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const appScript = fs.readFileSync(
  path.resolve(testRoot, "../../../app/src/main/assets/tesla/app.js"),
  "utf8",
);
const fallbackPolicy = fs.readFileSync(
  path.resolve(
    testRoot,
    "../../../app/src/main/java/com/pebble/tecomheadunit/browser/BrowserJpegFallbackPolicy.kt",
  ),
  "utf8",
);

test("browser and Android accept the same bounded high-resolution JPEG fallback", () => {
  assert.match(appScript, /const MAX_FRAME_BYTES = 2 \* 1024 \* 1024;/);
  assert.match(fallbackPolicy, /FRAME_STORE_MAX_BYTES = 2 \* 1024 \* 1024/);
});
