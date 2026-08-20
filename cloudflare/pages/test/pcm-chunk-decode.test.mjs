import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const appScript = readFileSync(
  path.resolve(cloudflareRoot, "..", "app", "src", "main", "assets", "tesla", "app.js"),
  "utf8",
);

function extractSchedulePcmChunkSource() {
  const startMarker = "function schedulePcmChunk(stream, pcmBytes)";
  const endMarker = "\n  function audioWebRtcChannelLabel";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing schedulePcmChunk");
  assert.notEqual(end, -1, "missing schedulePcmChunk end marker");
  return appScript.slice(start, end);
}

function createFakeAudioContext() {
  const created = [];
  const started = [];
  const context = {
    state: "running",
    currentTime: 0,
    createBuffer(channels, frames, sampleRate) {
      const channelData = Array.from({length: channels}, () => new Float32Array(frames));
      const buffer = {
        channels,
        frames,
        sampleRate,
        channelData,
        getChannelData(channel) {
          return channelData[channel];
        },
      };
      created.push(buffer);
      return buffer;
    },
    createBufferSource() {
      const source = {
        buffer: null,
        connect() {},
        addEventListener() {},
        start(when) {
          started.push({source, when});
        },
        stop() {},
      };
      return source;
    },
    destination: {},
  };
  return {context, created, started};
}

function loadSchedulePcmChunk({littleEndian, context}) {
  const sandbox = {
    PLATFORM_LITTLE_ENDIAN: littleEndian,
    AUDIO_MAX_SCHEDULE_AHEAD_SECONDS: 0.45,
    AUDIO_START_AHEAD_SECONDS: 0.04,
    audioContext: context,
    Uint8Array,
    Int16Array,
    Float32Array,
    DataView,
    Math,
  };
  vm.createContext(sandbox);
  vm.runInContext(`${extractSchedulePcmChunkSource()}\nthis.schedulePcmChunk = schedulePcmChunk;`, sandbox);
  return sandbox.schedulePcmChunk;
}

/** Reference decode matching the historical per-sample DataView implementation exactly. */
function expectedChannelData(pcmBytes, channels) {
  const frameBytes = channels * 2;
  const frameCount = Math.floor(pcmBytes.byteLength / frameBytes);
  const view = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, frameCount * frameBytes);
  const perChannel = Array.from({length: channels}, () => new Float32Array(frameCount));
  for (let channel = 0; channel < channels; channel += 1) {
    for (let frame = 0; frame < frameCount; frame += 1) {
      perChannel[channel][frame] = view.getInt16((frame * channels + channel) * 2, true) / 32768;
    }
  }
  return perChannel;
}

function pcmFixture({sampleCount, byteOffset}) {
  const backing = new Uint8Array(byteOffset + sampleCount * 2 + 3);
  for (let index = 0; index < backing.length; index += 1) {
    backing[index] = (index * 37 + 11) & 0xff;
  }
  return backing.subarray(byteOffset, byteOffset + sampleCount * 2);
}

test("aligned little-endian stereo chunk decodes identically to the DataView reference", () => {
  const {context, created} = createFakeAudioContext();
  const schedulePcmChunk = loadSchedulePcmChunk({littleEndian: true, context});
  const pcm = pcmFixture({sampleCount: 96, byteOffset: 0});
  const stream = {channels: 2, sampleRate: 48000, nextStartTime: 0, sources: new Set()};

  schedulePcmChunk(stream, pcm);

  assert.equal(created.length, 1);
  const expected = expectedChannelData(pcm, 2);
  assert.deepEqual([...created[0].getChannelData(0)], [...expected[0]]);
  assert.deepEqual([...created[0].getChannelData(1)], [...expected[1]]);
});

test("odd-offset mono chunk takes the aligned-copy path with identical samples", () => {
  const {context, created} = createFakeAudioContext();
  const schedulePcmChunk = loadSchedulePcmChunk({littleEndian: true, context});
  const pcm = pcmFixture({sampleCount: 41, byteOffset: 1});
  assert.equal(pcm.byteOffset % 2, 1, "fixture must exercise the unaligned branch");
  const stream = {channels: 1, sampleRate: 16000, nextStartTime: 0, sources: new Set()};

  schedulePcmChunk(stream, pcm);

  assert.equal(created.length, 1);
  const expected = expectedChannelData(pcm, 1);
  assert.deepEqual([...created[0].getChannelData(0)], [...expected[0]]);
});

test("big-endian fallback still decodes the little-endian wire format", () => {
  const {context, created} = createFakeAudioContext();
  const schedulePcmChunk = loadSchedulePcmChunk({littleEndian: false, context});
  const pcm = pcmFixture({sampleCount: 32, byteOffset: 0});
  const stream = {channels: 2, sampleRate: 48000, nextStartTime: 0, sources: new Set()};

  schedulePcmChunk(stream, pcm);

  assert.equal(created.length, 1);
  const expected = expectedChannelData(pcm, 2);
  assert.deepEqual([...created[0].getChannelData(0)], [...expected[0]]);
  assert.deepEqual([...created[0].getChannelData(1)], [...expected[1]]);
});

test("trailing partial frame bytes are dropped by frame flooring", () => {
  const {context, created} = createFakeAudioContext();
  const schedulePcmChunk = loadSchedulePcmChunk({littleEndian: true, context});
  const backing = pcmFixture({sampleCount: 12, byteOffset: 0});
  // 25 bytes for a stereo (4-byte) frame size: exactly 6 frames plus one dangling byte.
  const pcm = backing.subarray(0, 25);
  const stream = {channels: 2, sampleRate: 48000, nextStartTime: 0, sources: new Set()};

  schedulePcmChunk(stream, pcm);

  assert.equal(created.length, 1);
  assert.equal(created[0].frames, 6);
  const expected = expectedChannelData(pcm, 2);
  assert.deepEqual([...created[0].getChannelData(0)], [...expected[0]]);
});

test("suspended context schedules nothing", () => {
  const {context, created} = createFakeAudioContext();
  context.state = "suspended";
  const schedulePcmChunk = loadSchedulePcmChunk({littleEndian: true, context});
  const stream = {channels: 1, sampleRate: 16000, nextStartTime: 0, sources: new Set()};

  schedulePcmChunk(stream, pcmFixture({sampleCount: 8, byteOffset: 0}));

  assert.equal(created.length, 0);
});

test("scheduling advances nextStartTime by the chunk duration", () => {
  const {context, started} = createFakeAudioContext();
  const schedulePcmChunk = loadSchedulePcmChunk({littleEndian: true, context});
  context.currentTime = 1.0;
  const stream = {channels: 1, sampleRate: 16000, nextStartTime: 0, sources: new Set()};

  schedulePcmChunk(stream, pcmFixture({sampleCount: 160, byteOffset: 0}));

  assert.equal(started.length, 1);
  // A stale nextStartTime snaps to currentTime + AUDIO_START_AHEAD_SECONDS, then reserves
  // the chunk duration (160 frames / 16 kHz).
  assert.ok(Math.abs(started[0].when - 1.04) < 1e-9);
  assert.ok(Math.abs(stream.nextStartTime - (1.04 + 160 / 16000)) < 1e-9);
});
