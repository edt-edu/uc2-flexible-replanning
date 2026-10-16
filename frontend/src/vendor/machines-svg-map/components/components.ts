import { el } from '../svg/SvgNode.js';
import { ElementDrawing, group, rect } from '../machines/shapes.js';

/** Straight slide, e.g. at the exit of the conveyor belt or of the MPS conveyor. */
export const straightSlide: ElementDrawing = {
  width: 62,
  length: 69,
  parts: id => [
    group(`trapezeForm${id}`,
      el('polygon', { points: '0,0 62,0 47,53 15,53', fill: 'gray' }),
      rect(2, 2, 0, 0, { fill: 'red', id: `referencePoint${id}` })),
    group(`circleForm${id}`, el('circle', { r: 16, cx: 31, cy: 53, fill: 'gray' })),
    group(`arrivalPosition${id}`, el('circle', { r: 14, cx: 31, cy: 53, fill: 'lightblue', 'fill-opacity': 0.75 })),
  ],
};

/** Right-handed slide, e.g. at the exit of the sorting line: moves the token to the right, then down. */
export const rightHandedSlide: ElementDrawing = {
  width: 145,
  length: 36,
  parts: id => [
    group(`slideStart${id}`, rect(34, 36, 0, 0, { fill: 'gray' })),
    group(`slideRun${id}`, rect(130, 32, 0, 0, { fill: 'gray' })),
    group(`circleForm${id}`, el('circle', { r: 16, cx: 129, cy: 16, fill: 'gray' })),
    group(`arrivalPosition${id}`, el('circle', { r: 14, cx: 129, cy: 16, fill: 'lightblue', 'fill-opacity': 0.75 })),
  ],
};

/** Table outline (dashed), of any size. */
export function table(width: number, length: number): ElementDrawing {
  return {
    width: length,
    length: width,
    parts: id => [
      group(`tableBase${id}`,
        rect(length, width, 0, 0, { class: 'tableBase' }),
        rect(5, 5, 0, 0, { fill: 'red', id: `referencePoint${id}` })),
    ],
  };
}
