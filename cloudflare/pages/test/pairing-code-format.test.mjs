import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const assetRoot = path.resolve(testRoot, "../../../app/src/main/assets/web");

test("pairing input groups eight digits while the protocol receives no spaces", async () => {
  const script = await readFile(path.join(assetRoot, "app.js"), "utf8");
  const functionStart = script.indexOf("  function normalizePairingCode(candidate)");
  const functionEnd = script.indexOf("\n\n  async function pairWithCode(candidate)", functionStart);
  assert.notEqual(functionStart, -1);
  assert.notEqual(functionEnd, -1);

  const functions = script.slice(functionStart, functionEnd).replace(/^  /gmu, "");
  const { normalizePairingCode, formatPairingCodeInput } = Function(
    `${functions}; return { normalizePairingCode, formatPairingCodeInput };`,
  )();

  assert.equal(formatPairingCodeInput("12345678"), "1234 5678");
  assert.equal(formatPairingCodeInput("1234 5678"), "1234 5678");
  assert.equal(normalizePairingCode("1234 5678"), "12345678");
});

test("pairing input reserves one character for the visual separator", async () => {
  const page = await readFile(path.join(assetRoot, "index.html"), "utf8");
  assert.match(page, /id="code"[^>]+maxlength="9"[^>]+placeholder="0000 0000"/u);
});
