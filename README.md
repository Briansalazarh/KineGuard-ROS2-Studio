# KineGuard ROS2 Studio

Verifiable navigation compiler for autonomous mobile robots: perspective
camera images become metric trajectories only after a deterministic kinematic
kernel approves them, and approved trajectories are exported as standard
ROS 2 Nav2 packages.

Meta Global AI Developer Hackathon submission.

## How it works

1. **Calibration.** Four draggable floor points define a 3x3 projective
   homography (pixels <-> meters) for a corridor of declared width and length.
   Every scene declares its metric scale: `MEASURED_REFERENCE`,
   `NOMINAL_PRESET`, `NOMINAL_ASSUMED`, or `UNKNOWN`. Unknown scale halts the
   pipeline as `UNVERIFIED` and exports zero poses.
2. **Perception.** SAM 3.1 segments floor obstacles; Muse Spark grounds them
   when needed. Detections whose ground contact lies outside the calibrated
   floor are rejected, never shifted into the corridor.
3. **Planning with a formal veto.** A planner (Muse Spark / Muse Wonder via
   `verify_kinematic_safety` tool calling) proposes four metric waypoints.
   `KinematicSafetyKernel` is the final authority:
   - `a_eff = min(3.5, mu * g)`, `d_stop = v * 0.15 + v^2 / (2 * a_eff)`
   - lateral clearance `0.47 m` (`0.32` robot radius + `0.15` margin)
   - longitudinal clearance `0.32 + d_stop + 0.15`, applied directionally
     only when a segment heads toward an obstacle
   Rejections include a mathematical counterexample; a second turn may replan.
   Waypoint provenance is always labeled `LLM_TOOL_CALL`,
   `DETERMINISTIC_FALLBACK`, or `MOCK_SCRIPTED`. Insufficient perception
   (`NO_DETECTIONS`, `INVALID_DETECTION`, `PERCEPTION_ERROR`) can never
   become `APPROVED`.
4. **ROS 2 export.** Only `APPROVED` executions export poses. The bundle
   (`map.pgm` trinary `0/205/254`, `map.yaml`, `nav2_verified_waypoints.json`)
   covers exactly the declared corridor at `0.05 m/cell` with origin
   `[0, 0, 0]`, and is bound to its `executionId`, scene, and verification
   hash. Downloads use `GET /api/v1/ros2/export?executionId=<id>`; an unknown
   id returns 404 instead of another execution's package.

Red-team mode (Muse Image) synthesizes an adversarial floor hazard and runs
the same perception-verification loop. Voice commands (Muse Voice, 24 kHz
WAV) feed the operator directive. Without a Meta API key the system runs in
explicit `OFFLINE_DETERMINISTIC` mode and reports zero live calls.

## Run

Backend (Java 21, port 8080):

```powershell
cd backend
$env:META_API_KEY = "your_meta_api_key_here"   # optional; enables LIVE mode
mvn spring-boot:run
```

Frontend (React 18 + TypeScript, port 5173):

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. Select a preset or upload a photo, set the
corridor dimensions and scale type, calibrate the four floor points, and run
the verifiable pipeline.

## Tests

```powershell
cd backend;  mvn clean test   # 29 tests
cd frontend; npm test         # 15 tests
             npm run build
```

Positive and negative cases are kept as regression tests: approved bypasses,
fully blocked corridors, off-floor detections, unknown scale, and
cross-execution export isolation.

## Layout

- `backend/` — Spring Boot 3.3 service: homography, `KinematicSafetyKernel`,
  Meta API orchestration, ROS 2 export.
- `frontend/` — Studio UI: dual spatial viewports, verifiable agent loop
  panel, Nav2 package downloads.
- `docs/meta-api/` — Meta Model API protocol notes used by the integrations.
