import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.resolve(__dirname, "../src/utils/kinematics.ts"), "utf8");
const transpiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 },
});
const mod = await import(
  `data:text/javascript;base64,${Buffer.from(transpiled.outputText, "utf8").toString("base64")}`
);

test("Frontend kinematics mirror KinematicSafetyKernel exactly", () => {
  assert.equal(mod.ROBOT_RADIUS_METERS, 0.32);
  assert.equal(mod.REACTION_TIME_SEC, 0.15);
  assert.ok(Math.abs(mod.stoppingDistanceMeters(1.6, 0.62) - 0.606) < 0.001);
  assert.ok(Math.abs(mod.requiredClearanceMeters(1.6, 0.62) - 1.076) < 0.001);
  assert.equal(mod.lateralClearanceMeters(), 0.47);
  // Low friction must not be capped away: a_eff = 0.25 * 9.81 = 2.4525
  assert.ok(Math.abs(mod.effectiveDecelerationMps2(0.25) - 2.4525) < 1e-9);
  assert.ok(mod.stoppingDistanceMeters(1.6, 0.25) > mod.stoppingDistanceMeters(1.6, 0.62));
});

test("Scale provenance is sent by App and physics replicas contain no legacy factors", () => {
  const app = fs.readFileSync(path.resolve(__dirname, "../src/App.tsx"), "utf8");
  assert.ok(app.includes("scaleType: scale"), "App must send explicit scaleType");
  assert.ok(app.includes('setScaleType("UNKNOWN")'), "Uploaded photo starts with UNKNOWN scale");
  for (const file of ["VoiceChaosPanel.tsx", "DualSpatialViewports.tsx"]) {
    const src = fs.readFileSync(path.resolve(__dirname, `../src/components/${file}`), "utf8");
    assert.ok(!src.includes("dStop * 0.5"), `${file} must not halve stopping distance`);
    assert.ok(!src.includes("* 0.18"), `${file} must not use 0.18s reaction time`);
  }
  assert.ok(app.includes("entry.executionId === activeExecutionId"),
    "SSE logs must be filtered to the active execution before display");
  const dual = fs.readFileSync(path.resolve(__dirname, "../src/components/DualSpatialViewports.tsx"), "utf8");
  const gates = dual.match(/verificationStatus === "APPROVED" && verifiedWps\.length/g) || [];
  assert.ok(gates.length >= 2, "Both viewports must animate only APPROVED routes");
});
