/** Must mirror KinematicSafetyKernel exactly. Do not reintroduce factors here. */
export const ROBOT_RADIUS_METERS = 0.32;
export const REACTION_TIME_SEC = 0.15;
export const MAX_DECELERATION_MPS2 = 3.5;
export const SAFETY_MARGIN_METERS = 0.15;
const GRAVITY_MPS2 = 9.81;

export function effectiveDecelerationMps2(frictionMu: number): number {
  return Math.min(MAX_DECELERATION_MPS2, Math.max(0.05, frictionMu) * GRAVITY_MPS2);
}

export function stoppingDistanceMeters(velocityMs: number, frictionMu: number): number {
  const aEff = effectiveDecelerationMps2(frictionMu);
  return velocityMs * REACTION_TIME_SEC + (velocityMs * velocityMs) / (2 * aEff);
}

export function lateralClearanceMeters(): number {
  return ROBOT_RADIUS_METERS + SAFETY_MARGIN_METERS;
}

export function requiredClearanceMeters(velocityMs: number, frictionMu: number): number {
  return ROBOT_RADIUS_METERS + stoppingDistanceMeters(velocityMs, frictionMu) + SAFETY_MARGIN_METERS;
}
