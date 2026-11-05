import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const apiTsPath = path.resolve(__dirname, "../src/services/api.ts");

// Transpile the real frontend/src/services/api.ts with TypeScript compiler so we test the exact source code
const rawTsSource = fs
  .readFileSync(apiTsPath, "utf8")
  .replace(/import\.meta\.env\.VITE_API_BASE_URL/g, 'process.env.VITE_API_BASE_URL');

const transpiled = ts.transpileModule(rawTsSource, {
  compilerOptions: {
    module: ts.ModuleKind.ES2022,
    target: ts.ScriptTarget.ES2022,
  },
});

const dataUrl = `data:text/javascript;base64,${Buffer.from(
  transpiled.outputText,
  "utf8"
).toString("base64")}`;

const apiModule = await import(dataUrl);

test("P0-A.1: analyzeAndVerifyScene returns UNVERIFIED and 0 approved waypoints on HTTP 500", async () => {
  globalThis.fetch = async () => ({
    ok: false,
    status: 500,
    json: async () => ({ error: "Internal Server Error" }),
  });

  const res = await apiModule.analyzeAndVerifyScene({
    sceneId: "LOGISTICS_CORRIDOR_A",
    imageSource: "PRESET_SCENE",
    velocityMs: 1.6,
    frictionMu: 0.62,
  });

  assert.equal(res.verificationStatus, "UNVERIFIED");
  assert.equal(res.perceptionStatus, "PERCEPTION_ERROR");
  assert.equal(res.verifiedWaypoints.length, 0, "Must never fabricate approved waypoints on HTTP 500");
  assert.equal(res.agentTurns.length, 0, "Must not fabricate approved Turn 2 on HTTP 500");
  assert.equal(res.liveMetaApiCalls, 0);
  assert.equal(res.ros2Bundle.verificationStatus, "UNVERIFIED");

  const nav2Json = JSON.parse(res.ros2Bundle.nav2WaypointsJson);
  assert.equal(nav2Json.header.kinematic_approved, false);
  assert.equal(nav2Json.header.verification_status, "UNVERIFIED");
  assert.equal(nav2Json.poses.length, 0);
});

test("P0-A.2: analyzeAndVerifyScene and runRedTeamScenario return UNVERIFIED on connection refused", async () => {
  globalThis.fetch = async () => {
    throw new TypeError("fetch failed: ECONNREFUSED");
  };

  const analyzeRes = await apiModule.analyzeAndVerifyScene({
    sceneId: "LOGISTICS_CORRIDOR_A",
    imageSource: "USER_UPLOAD",
  });

  assert.equal(analyzeRes.verificationStatus, "UNVERIFIED");
  assert.equal(analyzeRes.verifiedWaypoints.length, 0);
  assert.equal(analyzeRes.agentTurns.some((t) => t.kernelVerdict?.approved), false);

  const redTeamRes = await apiModule.runRedTeamScenario({
    sceneId: "LOGISTICS_CORRIDOR_A",
    imageSource: "PRESET_SCENE",
  });

  assert.equal(redTeamRes.verificationStatus, "UNVERIFIED");
  assert.equal(redTeamRes.verifiedWaypoints.length, 0);
  assert.equal(redTeamRes.ros2Bundle.verificationStatus, "UNVERIFIED");
});

test("P0-A.3: analyzeAndVerifyScene rejects malformed backend JSON without fabricating approval", async () => {
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ unexpectedPayload: true }),
  });

  const res = await apiModule.analyzeAndVerifyScene({
    sceneId: "LOGISTICS_CORRIDOR_A",
  });

  assert.equal(res.verificationStatus, "UNVERIFIED");
  assert.equal(res.verifiedWaypoints.length, 0);
});

test("Phase2-1: App.tsx starts in IDLE on mount (no auto-POST) and concurrency controller enforces single POST per manual activation", async () => {
  const appTsxPath = path.resolve(__dirname, "../src/App.tsx");
  const mainTsxPath = path.resolve(__dirname, "../src/main.tsx");
  const appSource = fs.readFileSync(appTsxPath, "utf8");
  const mainSource = fs.readFileSync(mainTsxPath, "utf8");

  // Verify React.StrictMode is preserved in main.tsx
  assert.ok(mainSource.includes("React.StrictMode"), "React.StrictMode must remain enabled in main.tsx");

  // Verify App.tsx initial mount useEffect does NOT call executePipelineForState
  const mountEffectMatch = appSource.match(
    /useEffect\(\(\)\s*=>\s*\{\s*const initialPreset = REAL_SCENE_PRESETS\[0\];[\s\S]*?\},\s*\[\]\);/
  );
  assert.ok(mountEffectMatch, "Mount useEffect initializing preset 0 must exist");
  assert.equal(
    mountEffectMatch[0].includes("executePipelineForState"),
    false,
    "Mount useEffect must NOT call executePipelineForState on mount"
  );

  // Verify createPipelineExecutionController starts in IDLE and blocks concurrent triggers (producing exactly 1 POST)
  let postCounter = 0;
  let resolveInFlight;
  const controller = apiModule.createPipelineExecutionController(async (req) => {
    postCounter++;
    await new Promise((r) => {
      resolveInFlight = r;
    });
    return {
      executionId: req.executionId,
      verificationStatus: "APPROVED",
      perceptionStatus: "DETECTED",
      obstacleSource: "SAM_LIVE",
      waypointSource: "LLM_TOOL_CALL",
      obstacles: [{ id: "obs-1" }],
      agentTurns: [{ turnNumber: 1 }, { turnNumber: 2 }],
      rejectedWaypoints: [],
      verifiedWaypoints: [{ id: "W0", xMeters: 2, yMeters: 0.4, uPixel: 430, vPixel: 560, targetSpeedMs: 1.6 }],
      pipelineLogs: [],
    };
  });

  assert.equal(controller.getState().state, "IDLE");
  assert.equal(controller.getState().postCount, 0);

  // Manual activation #1 starts in-flight execution
  const firstPromise = controller.execute(
    { executionId: "exec-manual-01", sceneId: "LOGISTICS_CORRIDOR_A" },
    { allowPreempt: false }
  );
  assert.equal(controller.getState().state, "RUNNING");
  assert.equal(controller.getState().inFlight, true);
  assert.equal(postCounter, 1, "Manual activation must trigger exactly 1 POST");

  // Concurrent activation #2 while #1 is still in-flight must be blocked without firing a second POST
  const duplicateOutcome = await controller.execute(
    { executionId: "exec-duplicate-02", sceneId: "LOGISTICS_CORRIDOR_A" },
    { allowPreempt: false }
  );
  assert.equal(duplicateOutcome.status, "BLOCKED_CONCURRENT");
  assert.equal(postCounter, 1, "Concurrent trigger while busy must NOT fire a second POST");

  resolveInFlight();
  const firstOutcome = await firstPromise;
  assert.equal(firstOutcome.status, "COMPLETED");
  assert.equal(firstOutcome.executionId, "exec-manual-01");
  assert.equal(controller.getState().state, "COMPLETED");
  assert.equal(controller.getState().latestResult.executionId, "exec-manual-01");
});

test("Phase2-2: Out-of-order responses — slow earlier execution resolving after newer execution is discarded and never overwrites state", async () => {
  const resolvers = new Map();
  const completedIds = [];
  const discardedStaleIds = [];

  const controller = apiModule.createPipelineExecutionController(async (req) => {
    return await new Promise((resolve) => {
      resolvers.set(req.executionId, () =>
        resolve({
          executionId: req.executionId,
          sceneId: req.sceneId,
          verificationStatus: req.executionId === "exec-new-02" ? "APPROVED" : "REJECTED",
          perceptionStatus: "DETECTED",
          obstacleSource: "SAM_LIVE",
          waypointSource: req.executionId === "exec-new-02" ? "LLM_TOOL_CALL" : "NONE",
          obstacles: [{ id: "obs-1" }],
          agentTurns: [],
          rejectedWaypoints: [],
          verifiedWaypoints:
            req.executionId === "exec-new-02"
              ? [{ id: "W0", xMeters: 2.0, yMeters: 0.4, uPixel: 430, vPixel: 560, targetSpeedMs: 1.6 }]
              : [],
          pipelineLogs: [],
        })
      );
    });
  });

  // Start execution #1 (slow)
  const p1 = controller.execute(
    { executionId: "exec-old-01", sceneId: "LOGISTICS_CORRIDOR_A" },
    {
      allowPreempt: true,
      onComplete: (res) => completedIds.push(res.executionId),
      onDiscardStale: (staleId) => discardedStaleIds.push(staleId),
    }
  );

  // Preempt with execution #2 (newer) before #1 finishes
  const p2 = controller.execute(
    { executionId: "exec-new-02", sceneId: "COLD_STORAGE_BAY_B" },
    {
      allowPreempt: true,
      onComplete: (res) => completedIds.push(res.executionId),
      onDiscardStale: (staleId) => discardedStaleIds.push(staleId),
    }
  );

  // Resolve newer execution #2 FIRST
  resolvers.get("exec-new-02")();
  const outcome2 = await p2;
  assert.equal(outcome2.status, "COMPLETED");
  assert.equal(controller.getState().latestResult.executionId, "exec-new-02");
  assert.equal(controller.getState().latestResult.verificationStatus, "APPROVED");

  // Now resolve older execution #1 AFTER #2 has already completed (out-of-order arrival)
  resolvers.get("exec-old-01")();
  const outcome1 = await p1;
  assert.equal(outcome1.status, "DISCARDED_STALE");
  assert.deepEqual(discardedStaleIds, ["exec-old-01"]);
  assert.deepEqual(completedIds, ["exec-new-02"], "Stale response must never invoke onComplete");
  assert.equal(
    controller.getState().latestResult.executionId,
    "exec-new-02",
    "Stale response must NEVER overwrite newer execution state"
  );
  assert.equal(controller.getState().latestResult.verificationStatus, "APPROVED");
});

test("Export is bound to executionId and never substitutes another execution on 404", async () => {
  const urls = [];
  globalThis.fetch = async (url) => {
    urls.push(String(url));
    return {
      ok: true,
      status: 200,
      json: async () => ({
        sceneId: "LOGISTICS_CORRIDOR_A", executionId: "exec-iso-A",
        verificationStatus: "APPROVED", verificationHash: "sha256:hash-a",
        mapPgmAscii: "P2", mapYaml: "image: map.pgm",
        nav2WaypointsJson: "{\"poses\":[1,2,3,4]}",
        gridWidthCells: 80, gridHeightCells: 120, resolutionMetersPerPixel: 0.05,
      }),
    };
  };
  const bundleA = await apiModule.exportRos2Bundle("exec-iso-A");
  assert.ok(urls[0].includes("executionId=exec-iso-A"));
  assert.equal(bundleA.executionId, "exec-iso-A");
  assert.equal(bundleA.verificationHash, "sha256:hash-a");

  globalThis.fetch = async () => ({ ok: false, status: 404, json: async () => ({}) });
  const missing = await apiModule.exportRos2Bundle("exec-iso-missing");
  assert.equal(missing.verificationStatus, "UNVERIFIED");
  assert.equal(JSON.parse(missing.nav2WaypointsJson).poses.length, 0,
    "Unknown execution must never receive another execution's poses");
});

test("Phase2-3: Meta API / HTTP error preserves correlated executionId and emits ERROR log without artificial approval", async () => {
  globalThis.fetch = async () => ({
    ok: false,
    status: 503,
    json: async () => ({ error: "Meta API Upstream Timeout" }),
  });

  const res = await apiModule.analyzeAndVerifyScene({
    executionId: "exec-http503-corr",
    sceneId: "LOGISTICS_CORRIDOR_A",
    imageSource: "PRESET_SCENE",
  });

  assert.equal(res.executionId, "exec-http503-corr");
  assert.equal(res.verificationStatus, "UNVERIFIED");
  assert.equal(res.perceptionStatus, "PERCEPTION_ERROR");
  assert.equal(res.verifiedWaypoints.length, 0);
  assert.equal(res.failedMetaApiCalls, 1);
  assert.equal(res.pipelineLogs.length, 1);
  assert.equal(res.pipelineLogs[0].executionId, "exec-http503-corr");
  assert.equal(res.pipelineLogs[0].level, "ERROR");
});


