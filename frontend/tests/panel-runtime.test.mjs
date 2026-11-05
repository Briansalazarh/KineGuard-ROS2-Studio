import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { createRequire } from "node:module";
import { buildSync } from "esbuild";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";

const bundle = buildSync({
  stdin: {
    contents: `export { VerifiableAgentLoopPanel } from './src/components/VerifiableAgentLoopPanel';
      export { DualSpatialViewports, interpolateAlongWaypoints } from './src/components/DualSpatialViewports';
      export { TelemetryMetricsBar } from './src/components/TelemetryMetricsBar';
      export { REAL_SCENE_PRESETS } from './src/utils/realScenePresets';
      export { analyzeAndVerifyScene, runRedTeamScenario } from './src/services/api';
      export { formatMetric } from './src/utils/formatMetric';`,
    resolveDir: process.cwd(),
  },
  bundle: true,
  packages: "external",
  platform: "node",
  format: "cjs",
  write: false,
  define: { "import.meta.env.VITE_API_BASE_URL": '"http://fixture.invalid"' },
});
const module = { exports: {} };
new Function("require", "module", "exports", bundle.outputFiles[0].text)(
  createRequire(import.meta.url), module, module.exports
);
const { VerifiableAgentLoopPanel, DualSpatialViewports, interpolateAlongWaypoints, TelemetryMetricsBar, REAL_SCENE_PRESETS,
  analyzeAndVerifyScene, runRedTeamScenario, formatMetric } = module.exports;
// Generated from VanguardEngineTests' mocked LIVE_META_API orchestrator response.
// Coordinate keys were restored to the pre-fix Jackson spelling confirmed with JShell.
const fixture = JSON.parse(fs.readFileSync(new URL("./fixtures/legacy-live-scene.json", import.meta.url), "utf8"));
const renderPanel = (response) => renderToStaticMarkup(React.createElement(VerifiableAgentLoopPanel, {
  verifiableResult: response, onDownloadRos2File() {},
})).replace(/<[^>]*>/g, "");

test("Previously failing LIVE_META_API JSON renders real coordinates through both API entry points", async (t) => {
  const point = fixture.agentTurns[0].proposedWaypoints[0];
  assert.equal(point.xMeters, undefined);
  assert.equal(point.xmeters, 2);
  assert.throws(() => point.xMeters.toFixed(2), TypeError);
  t.mock.method(globalThis, "fetch", async () => ({ ok: true, json: async () => structuredClone(fixture) }));
  for (const request of [analyzeAndVerifyScene, runRedTeamScenario]) {
    const result = await request({ executionId: fixture.executionId });
    assert.equal(result.executionMode, "LIVE_META_API");
    assert.equal(result.verificationStatus, fixture.verificationStatus);
    assert.equal(result.agentTurns[0].proposedWaypoints[0].xMeters, 2);
    assert.equal(result.agentTurns[0].proposedWaypoints[0].yMeters, 0.45);
    assert.equal(result.agentTurns[0].kernelVerdict.evaluatedWaypoints[0].uPixel,
      fixture.agentTurns[0].kernelVerdict.evaluatedWaypoints[0].upixel);
    assert.equal(result.verifiedWaypoints[0].vPixel, fixture.verifiedWaypoints[0].vpixel);
    assert.equal(result.obstacles[0].pixelBox.xMin, fixture.obstacles[0].pixelBox.xmin);
    const text = renderPanel(result);
    assert.match(text, /W0: \(2\.00m, 0\.45m\) @ 1\.6m\/s/);
    assert.match(text, /REJECTED/);
    assert.match(text, /APPROVED/);
    assert.doesNotMatch(text, /NaN|undefined|N\/A/);
    const preset = REAL_SCENE_PRESETS[0];
    assert.doesNotThrow(() => renderToStaticMarkup(React.createElement(DualSpatialViewports, {
      presets: REAL_SCENE_PRESETS, activePreset: preset, calibrationPoints: preset.calibrationPoints,
      sceneDataUri: "", verifiableResult: result, velocityMs: 1.6, frictionMu: 0.62, isAnalyzing: false,
    })));
    // The canonical response also works, preserving valid zero coordinates.
    result.agentTurns[0].proposedWaypoints[0].xMeters = 0;
    t.mock.method(globalThis, "fetch", async () => ({ ok: true, json: async () => result }));
    assert.match(renderPanel(await request({})), /W0: \(0\.00m, 0\.45m\)/);
    t.mock.restoreAll();
    t.mock.method(globalThis, "fetch", async () => ({ ok: true, json: async () => structuredClone(fixture) }));
  }
});

test("Missing or invalid numerical display values render N/A without invented metrics", () => {
  for (const value of [undefined, null, NaN, Infinity, -Infinity, "1.6"]) {
    assert.equal(formatMetric(value, 2, "m"), "N/A");
    const response = structuredClone(fixture);
    response.agentTurns[0].latencyMs = value;
    response.agentTurns[0].minClearanceMeters = value;
    response.agentTurns[0].kernelVerdict.requiredClearanceMeters = value;
    response.agentTurns[0].proposedWaypoints[0].targetSpeedMs = value;
    const text = renderPanel(response);
    assert.match(text, /Latency: N\/A/);
    assert.match(text, /minClearance=N\/A \| lateralReq=0\.47m \| longitudinalReq=N\/A/);
    assert.match(text, /W0: \(N\/A, N\/A\) @ N\/A/);
    assert.doesNotMatch(text, /NaN|undefined|Infinity/);
  }
  assert.equal(formatMetric(0, 2, "m"), "0.00m");
});

test("Missing required coordinates halt the response as UNVERIFIED", async (t) => {
  const invalid = structuredClone(fixture);
  delete invalid.agentTurns[0].proposedWaypoints[0].xmeters;
  t.mock.method(globalThis, "fetch", async () => ({ ok: true, json: async () => invalid }));
  const result = await analyzeAndVerifyScene({});
  assert.equal(result.verificationStatus, "UNVERIFIED");
  assert.equal(result.verifiedWaypoints.length, 0);
  assert.match(renderPanel(result), /VERIFICATION HALTED/);
});

test("Unavailable telemetry metrics display N/A", () => {
  const html = renderToStaticMarkup(React.createElement(TelemetryMetricsBar, {
    metrics: null, connected: false, streamMode: "IDLE",
  }));
  assert.equal((html.match(/N\/A/g) || []).length, 6);
  assert.doesNotMatch(html, /12\.6|0\.34|0\.00%/);
});

test("Animated robot follows the verified polyline exactly, paced by segment length", () => {
  const wps = [
    { uPixel: 0, vPixel: 0, xMeters: 0, yMeters: 0 },
    { uPixel: 100, vPixel: 0, xMeters: 1, yMeters: 0 },
    { uPixel: 100, vPixel: 200, xMeters: 1, yMeters: 2 },
  ];
  const start = interpolateAlongWaypoints(wps, 0);
  assert.equal(start.xM, 0); assert.equal(start.yM, 0);
  const atW1 = interpolateAlongWaypoints(wps, 5500 / 3);
  assert.ok(Math.abs(atW1.xM - 1) < 1e-9 && Math.abs(atW1.yM) < 1e-9, "Must pass exactly through W1");
  const mid = interpolateAlongWaypoints(wps, 2750);
  assert.ok(Math.abs(mid.yM - 0.5) < 1e-9 && Math.abs(mid.v - 50) < 1e-9);
  const loop = interpolateAlongWaypoints(wps, 5500);
  assert.equal(loop.xM, 0); assert.equal(loop.yM, 0);
});

test("Metric map only labels Turn 2 verified when there is an approved route", () => {
  const preset = REAL_SCENE_PRESETS[0];
  const renderMap = (verifiableResult) => renderToStaticMarkup(React.createElement(DualSpatialViewports, {
    presets: [], activePreset: preset, calibrationPoints: preset.calibrationPoints,
    sceneDataUri: "", verifiableResult, velocityMs: 0.6, frictionMu: 0.62, isAnalyzing: false,
  }));
  assert.match(renderMap(fixture), /T2 Verified/);
  assert.doesNotMatch(renderMap({ ...fixture, verificationStatus: "REJECTED", verifiedWaypoints: [] }), /T2 Verified/);
});
