import { el } from '../svg/SvgNode.js';
import { ElementDrawing, group, rect, woodBase } from './shapes.js';

/** Conveyor belt, with its belt (animated while running) and its feed / swap light barriers. */
export const conveyorBelt: ElementDrawing = {
  width: 260,
  length: 186,

  parts: id => [
    woodBase(id, 260, 186),
    group(`plasticBase${id}`, rect(257, 186, 0, 0, { class: 'plasticBase' })),
    group(`electronicCard${id}`, rect(108, 80, 75, 100, { class: 'electronicCard' })),
    group(`conveyorZone${id}`,
      rect(270, 50, 0, 5, { id: `beltSurface${id}`, class: 'conveyorZone movingPart', fill: `url(#beltPattern${id})` })),
    group(`sensorsGroup${id}`,
      rect(20, 80, 30, -10, { id: `sensorSwap${id}`, class: 'lightBarrier' }),
      rect(20, 80, 220, -10, { id: `sensorFeed${id}`, class: 'lightBarrier' })),
  ],

  /** Belt stripes, scrolled by the `beltForward` / `beltBackward` animations the runtime starts. */
  defs: id => [
    el('pattern', { id: `beltPattern${id}`, x: 0, y: 0, width: 24, height: 24, patternUnits: 'userSpaceOnUse', patternTransform: 'translate(0,0)' },
      rect(24, 24, 0, 0, { fill: '#4c4c4c' }),
      rect(10, 24, 0, 0, { fill: '#6e6e6e' }),
      rect(4, 24, 14, 0, { fill: '#838383' }),
      el('animateTransform', { id: `beltForward${id}`, attributeName: 'patternTransform', type: 'translate', from: '0 0', to: '24 0', dur: '0.8s', begin: 'indefinite', repeatCount: 'indefinite' }),
      el('animateTransform', { id: `beltBackward${id}`, attributeName: 'patternTransform', type: 'translate', from: '0 0', to: '-24 0', dur: '0.8s', begin: 'indefinite', repeatCount: 'indefinite' })),
  ],
};
