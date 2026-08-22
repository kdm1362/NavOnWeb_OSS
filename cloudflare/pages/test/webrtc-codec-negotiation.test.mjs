import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const appScript = readFileSync(
  path.resolve(testRoot, "../../../app/src/main/assets/web/app.js"),
  "utf8",
);

test("browser keeps the native WebRTC codec offer and its RTX dependencies", () => {
  assert.match(appScript, /addTransceiver\('video', \{direction: 'recvonly'\}\)/);
  assert.doesNotMatch(appScript, /setCodecPreferences/);
  assert.doesNotMatch(appScript, /preferredPayloadType/);
});
