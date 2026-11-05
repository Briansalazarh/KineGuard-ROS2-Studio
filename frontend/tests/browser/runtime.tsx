// Run with npm run dev, then open /tests/browser/runtime.html.
// This harness uses the real App and API entry points. All network responses are local.
import React from "react";
import { createRoot } from "react-dom/client";
import { flushSync } from "react-dom";
import App from "../../src/App";
import { PanelErrorBoundary } from "../../src/components/PanelErrorBoundary";
import fixture from "../fixtures/legacy-live-scene.json";
import "../../src/index.css";

const status = document.getElementById("test-status")!;
let sceneRequests = 0;
let uncaughtErrors = 0;
let testingBoundary = false;
const results: string[] = [];
function report() {
  status.textContent = `LOCAL FIXTURE ONLY | ${results.join(" | ")} | Scene requests: ${sceneRequests} | Uncaught errors: ${uncaughtErrors}`;
}
window.addEventListener("error", () => {
  if (!testingBoundary) uncaughtErrors++;
  report();
});
window.addEventListener("unhandledrejection", () => { uncaughtErrors++; report(); });
window.fetch = async (input) => {
  const url = String(input);
  if (url.endsWith("/scene/analyze-and-verify") || url.endsWith("/scene/red-team")) {
    sceneRequests++;
    report();
    return new Response(JSON.stringify(fixture), { headers: { "Content-Type": "application/json" } });
  }
  if (url.endsWith("/ros2/export")) return new Response(JSON.stringify(fixture.ros2Bundle));
  throw new Error(`Unexpected request in local fixture test: ${url}`);
};
// Keep the app's telemetry subscription inert, without opening a connection.
window.EventSource = class extends EventTarget { close() {} } as unknown as typeof EventSource;

function assert(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
  results.push(`PASS ${message}`);
  report();
}

const boundaryContainer = document.getElementById("boundary-test")!;
const boundaryRoot = createRoot(boundaryContainer);
function FailingChild({ fail }: { fail: boolean }) {
  if (fail) throw new Error("Intentional panel regression error");
  return <p>Panel recovered</p>;
}
function renderBoundary(fail: boolean, resetKey: string) {
  flushSync(() => boundaryRoot.render(<>
    <p>Sibling still mounted</p>
    <PanelErrorBoundary panelName="Regression panel" resetKey={resetKey}>
      <FailingChild fail={fail} />
    </PanelErrorBoundary>
  </>));
}
testingBoundary = true;
renderBoundary(true, "first");
assert(!!boundaryContainer.querySelector('[role="alert"]') && boundaryContainer.textContent!.includes("Sibling still mounted"), "boundary isolation");
renderBoundary(false, "first");
flushSync(() => boundaryContainer.querySelector("button")!.click());
assert(boundaryContainer.textContent!.includes("Panel recovered"), "boundary retry");
renderBoundary(true, "first");
renderBoundary(false, "second");
assert(boundaryContainer.textContent!.includes("Panel recovered"), "boundary new execution recovery");
testingBoundary = false;
boundaryRoot.unmount();

flushSync(() => createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>));
const observer = new MutationObserver(() => {
  const text = document.getElementById("root")!.textContent || "";
  if (text.includes("W0: (2.00m, 0.45m) @ 1.6m/s")) {
    assert(text.includes("TURN 1") && text.includes("TURN 2"), "legacy response renders both turns");
    assert(uncaughtErrors === 0, "zero uncaught runtime errors");
    observer.disconnect();
  }
});
observer.observe(document.getElementById("root")!, { childList: true, subtree: true });
report();
