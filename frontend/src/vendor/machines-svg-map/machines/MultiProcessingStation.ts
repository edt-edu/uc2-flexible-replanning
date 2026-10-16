import { el } from '../svg/SvgNode.js';
import { axisCross, ElementDrawing, group, rect, starPoints, woodBase } from './shapes.js';

/** Center of the saw, which the runtime rotates. */
export const SAW_CENTER = { x: 156, y: 180 };

const TURNTABLE = { x: 174, y: 120, minRadius: 40, maxRadius: 64 };

/** Multi processing station with oven: turntable, saw, slider and its in / out light barriers. */
export const multiProcessingStation: ElementDrawing = {
  width: 438,
  length: 310,

  parts: id => [
    woodBase(id, 438, 310),
    group(`plasticBase${id}`, rect(410, 258, 25, 25, { class: 'plasticBase' })),
    group(`conveyorZone${id}`, rect(31, 132, 46, 20, { class: 'conveyorZone' })),
    group(`slider${id}`, rect(60, 30, 46, 105, { class: 'slider' })),
    group(`electronicCard${id}`, rect(126, 86, 160, 196, { class: 'electronicCard' })),
    group(`oven${id}`,
      rect(180, 106, 252, 86, { fill: 'none', stroke: 'black' }),
      rect(90, 30, 322, 86, { fill: 'none', stroke: 'black' })),
    group(`sensorsGroup${id}`,
      rect(120, 15, 300, 58, { id: `sensorIn${id}`, class: 'lightBarrier' }),
      rect(96, 15, 10, 42, { id: `sensorOut${id}`, class: 'lightBarrier' })),
    group(`armAccessZone${id}`, rect(260, 15, 156, 60, { class: 'accessZone' })),
    group(`turntableAccessZone${id}`,
      el('circle', { r: TURNTABLE.maxRadius, cx: TURNTABLE.x, cy: TURNTABLE.y, class: 'accessZone', mask: `url(#turntableMask${id})` })),
    axisCross(`turntableAxis${id}`, TURNTABLE.x, TURNTABLE.y),
    group(`ovenSlideAccessZone${id}`, rect(30, 90, 348, 50, { class: 'accessZone' })),
    group(`saw${id}`,
      el('polygon', { points: starPoints(SAW_CENTER.x, SAW_CENTER.y, 7, 15, 10), stroke: 'black', fill: 'none' })),
  ],

  /** Quarter of the turntable ring the arm can reach. */
  defs: id => [
    el('mask', { id: `turntableMask${id}` },
      rect(TURNTABLE.maxRadius * 2, TURNTABLE.maxRadius * 5, TURNTABLE.x - TURNTABLE.maxRadius, TURNTABLE.y - TURNTABLE.maxRadius, { fill: 'white' }),
      el('circle', { r: TURNTABLE.minRadius, cx: TURNTABLE.x, cy: TURNTABLE.y, fill: 'black' }),
      rect(TURNTABLE.maxRadius, TURNTABLE.maxRadius, TURNTABLE.x - TURNTABLE.maxRadius, TURNTABLE.y - TURNTABLE.maxRadius)),
  ],
};
