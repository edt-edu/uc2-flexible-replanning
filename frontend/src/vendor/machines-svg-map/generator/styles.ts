/** Colors of the machine states, for an application legend matching the drawing. */
export const LEGEND_COLORS = {
  notOperational: '#dc2626',
  notInitialized: '#fef3c7',
  accessZone: 'rgba(59, 130, 246, 0.3)',
  movingPart: '#374151',
  lightBarrier: '#fef9c3',
  lightBarrierTriggered: '#facc15',
  gripperVacuumOn: '#2563eb',
  tokenUnknownColor: '#9ca3af',
  tokenWhite: '#ffffff',
  tokenRed: '#dc2626',
  tokenBlue: '#2563eb',
  tokenAnomaly: '#dc2626',
} as const;

/**
 * Style sheet embedded in the SVG. The runtime only toggles the classes below (`active`, `machineNotOperational`,
 * ...), so the look of each state can be changed here or overridden by the application's CSS.
 */
export function styleSheet(accessibleZoneOpacity: number): string {
  return `
    .accessZone { fill: lightblue; fill-opacity: ${accessibleZoneOpacity}; }
    .woodBase { fill: gray; stroke: black; transition: fill 200ms ease; }
    .tableBase { fill: burlywood; fill-opacity: 0; stroke: black; stroke-width: 1; stroke-dasharray: 5, 5; }
    .plasticBase { fill: darkgray; fill-opacity: 0.5; stroke: black; transition: fill 200ms ease, fill-opacity 200ms ease; }
    .conveyorZone { fill: dimgray; }
    .lightBarrier { fill: ${LEGEND_COLORS.lightBarrier}; fill-opacity: 1; stroke: black; }
    .lightBarrier.active { fill: ${LEGEND_COLORS.lightBarrierTriggered}; fill-opacity: 1; }
    .colorLightBarrier { fill-opacity: 0.2; stroke: black; }
    .electronicCard { fill: lightgray; stroke: black; }
    .slide, .slider { fill: dimgray; stroke: black; }
    .slider.active { fill: #f59e0b; }
    .ejector { fill: lightgray; stroke: black; }
    .storage { fill: lightgray; stroke: black; }
    .storage.active { fill: #93c5fd; }
    .movingPart { transition: fill 150ms ease, stroke 150ms ease; }
    .movingPartNotOperational { fill: ${LEGEND_COLORS.notOperational} !important; stroke: #7f1d1d !important; }
    .machineNotOperational .woodBase { fill: ${LEGEND_COLORS.notOperational} !important; }
    .machineNotOperational .plasticBase { fill: #ef4444 !important; fill-opacity: 0.6 !important; }
    .machineNotInitialized .woodBase { fill: ${LEGEND_COLORS.notInitialized} !important; }
    .machineNotInitialized .plasticBase { fill: #fde68a !important; fill-opacity: 0.65 !important; }
    .machinePopup { opacity: 0; pointer-events: none; transition: opacity 200ms ease; }
    .machinePopupVisible { opacity: 1; }
    .machinePopupBox { fill: white; stroke: ${LEGEND_COLORS.notOperational}; stroke-width: 3; rx: 6; }
    .machinePopupText { fill: #7f1d1d; font-family: sans-serif; font-size: 18px; font-weight: bold; }
    .machinePopupTime { fill: #991b1b; font-family: sans-serif; font-size: 14px; }
    .sawActive { stroke: ${LEGEND_COLORS.notOperational} !important; stroke-width: 2; }
    .gripperActive { fill: ${LEGEND_COLORS.gripperVacuumOn} !important; }
    .armLowered { stroke: ${LEGEND_COLORS.gripperVacuumOn} !important; stroke-dasharray: 6 3; }
    .elementLabel { font-family: sans-serif; font-size: 10px; fill: black; }
  `;
}
