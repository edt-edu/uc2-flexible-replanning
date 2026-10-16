import { AttributeValue, el, SvgNode } from '../svg/SvgNode.js';

/** Drawing of one kind of layout element, in its own coordinate system (reference point at 0, 0). */
export interface ElementDrawing {
  /** Size of the base, along x then y, in mm. */
  width: number;
  length: number;
  /** Parts of the element; the ids of those the runtime updates end with the element id. */
  parts(id: string): SvgNode[];
  /** Definitions (patterns, masks, ...) to add to the SVG `<defs>`. */
  defs?(id: string): SvgNode[];
}

export function rect(width: number, height: number, x = 0, y = 0, attrs: Record<string, AttributeValue> = {}): SvgNode {
  return el('rect', { width, height, x: x || undefined, y: y || undefined, ...attrs });
}

export function group(id: string | undefined, ...children: SvgNode[]): SvgNode {
  return el('g', { id }, ...children);
}

/** Wood base with the red mark of the reference point. */
export function woodBase(id: string, width: number, length: number): SvgNode {
  return group(`woodBase${id}`,
    rect(width, length, 0, 0, { class: 'woodBase' }),
    rect(5, 5, 0, 0, { fill: 'red', id: `referencePoint${id}` }));
}

/** Cross in a circle marking a rotation axis. */
export function axisCross(id: string, cx: number, cy: number, size = 5): SvgNode {
  const line = (x1: number, y1: number, x2: number, y2: number) =>
    el('line', { x1, y1, x2, y2, stroke: 'black', 'stroke-width': 1 });
  return el('g', { id },
    el('circle', { r: size, cx, cy, stroke: 'black', fill: 'none' }),
    line(cx - size, cy + size, cx + size, cy - size),
    line(cx - size, cy - size, cx + size, cy + size));
}

/** Points of a star polygon (first point at the top). */
export function starPoints(cx: number, cy: number, spikes: number, outerRadius: number, innerRadius: number): string {
  const points: [number, number][] = [[cx, cy - outerRadius]];
  let rotation = Math.PI / 2 * 3;
  const step = Math.PI / spikes;
  for (let i = 0; i < spikes; i++) {
    points.push([cx + Math.cos(rotation) * outerRadius, cy + Math.sin(rotation) * outerRadius]);
    rotation += step;
    points.push([cx + Math.cos(rotation) * innerRadius, cy + Math.sin(rotation) * innerRadius]);
    rotation += step;
  }
  points.push([cx, cy - outerRadius]);
  return points.map(([x, y]) => `${Math.round(x * 1e6) / 1e6},${Math.round(y * 1e6) / 1e6}`).join(' ');
}
