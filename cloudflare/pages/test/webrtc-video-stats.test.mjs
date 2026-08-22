import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const appScript = fs.readFileSync(
  path.resolve(testRoot, "../../../app/src/main/assets/web/app.js"),
  "utf8",
);

test("connected video publishes measured bitrate, frame size and QP diagnostics", () => {
  assert.match(appScript, /const WEBRTC_VIDEO_STATS_INTERVAL_MILLIS = 5000;/);
  assert.match(appScript, /entry\.type === 'inbound-rtp'/);
  assert.match(appScript, /inbound\.bytesReceived/);
  assert.match(appScript, /inbound\.framesDecoded/);
  assert.match(appScript, /inbound\.qpSum/);
  assert.match(appScript, /NAVONWEB_VIDEO_STATS size=/);
  assert.match(appScript, /startWebRtcVideoStats\(peer, generation\);/);
  assert.match(appScript, /webRtcGeneration \+= 1;\s+stopWebRtcVideoStats\(\);/);
});
