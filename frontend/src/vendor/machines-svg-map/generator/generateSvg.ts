import { rightHandedSlide, straightSlide, table } from '../components/components.js';
import { ElementDrawing } from '../machines/shapes.js';
import { conveyorBelt } from '../machines/ConveyorBelt.js';
import { highBayWarehouse } from '../machines/HighBayWarehouse.js';
import { multiProcessingStation } from '../machines/MultiProcessingStation.js';
import { sortingLine } from '../machines/SortingLine.js';
import { vacuumGripper } from '../machines/VacuumGripper.js';
import { FactoryLayout, isCustomSizeComponent, isMachine, LayoutElement } from '../layout/FactoryLayout.js';
import { applyMatrix, el, parseTransform, serialize, SvgNode } from '../svg/SvgNode.js';
import { styleSheet } from './styles.js';

export interface GenerateOptions {
  /** Writes the position of each machine above it. */
  addPositions?: boolean;
  /** Writes the size of each machine above it. */
  addDimensions?: boolean;
  /** Opacity of the zones the machines can reach, between 0 and 1. Default 0.5. */
  accessibleZoneOpacity?: number;
  /** Id of the `<svg>` element. Default `shopfloor-svg`. */
  svgId?: string;
}

const DRAWINGS: Record<string, ElementDrawing> = {
  CB: conveyorBelt,
  HBW: highBayWarehouse,
  SLC: sortingLine,
  MPO: multiProcessingStation,
  VGR: vacuumGripper,
  S_SLI: straightSlide,
  RH_SLI: rightHandedSlide,
};

export function drawingOf(element: LayoutElement): ElementDrawing {
  return isCustomSizeComponent(element) ? table(element.width, element.length) : DRAWINGS[element.kind];
}

/** Transform placing an element: rotation around its reference point, then translation to its position. */
export function placement(element: LayoutElement): string | undefined {
  const parts = [
    element.x !== 0 || element.y !== 0 ? `translate(${element.x} ${element.y})` : '',
    element.degrees !== 0 ? `rotate(${element.degrees})` : '',
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(' ') : undefined;
}

/** Builds the SVG tree of the layout (see {@link generateSvg}). */
export function buildScene(layout: FactoryLayout, options: GenerateOptions = {}): SvgNode {
  const opacity = options.accessibleZoneOpacity ?? 0.5;
  if (!(opacity >= 0 && opacity <= 1)) {
    throw new RangeError(`accessibleZoneOpacity must be between 0 and 1, not ${opacity}`);
  }
  const withLabels = options.addPositions || options.addDimensions;

  const defs: SvgNode[] = [];
  const elements: SvgNode[] = [];
  const popups: SvgNode[] = [];
  const labels: SvgNode[] = [];
  for (const element of layout.positions) {
    const drawing = drawingOf(element);
    defs.push(...drawing.defs?.(element.id) ?? []);
    const machine = isMachine(element);
    elements.push(el('g', {
      id: element.id,
      class: machine ? 'machine' : 'component',
      'data-kind': element.kind,
      'data-telemetry-id': machine ? element.telemetryId : undefined,
      transform: placement(element),
    }, el('title', {}, element.name), ...drawing.parts(element.id)));

    if (machine) {
      popups.push(popup(element, drawing, layout));
    }
    if (machine && withLabels) {
      labels.push(el('g', { id: `label${element.id}`, transform: `translate(${element.x} ${element.y})` },
        options.addPositions ? el('text', { x: 0, y: -10, class: 'elementLabel' }, `x: ${element.x}mm, y: ${element.y}mm`) : null,
        options.addDimensions ? el('text', { x: 0, y: -25, class: 'elementLabel' }, `W: ${drawing.width}mm L: ${drawing.length}mm`) : null));
    }
  }

  return el('svg', {
    id: options.svgId ?? 'shopfloor-svg',
    xmlns: 'http://www.w3.org/2000/svg',
    version: '1.1',
    width: layout.xSize,
    height: layout.ySize,
    viewBox: `0 0 ${layout.xSize} ${layout.ySize}`,
    'data-layout': layout.name,
  },
  el('style', {}, styleSheet(opacity)),
  el('defs', {}, ...defs),
  rulers(layout.xSize, layout.ySize, !!withLabels),
  ...elements,
  el('g', { id: 'popups' }, ...popups),
  labels.length > 0 ? el('g', { id: 'labels' }, ...labels) : null);
}

/**
 * Generates the SVG of a factory layout, as markup. Works without any DOM (Node, browser, worker).
 *
 * Every machine is a `<g id="{machine id}">` group; the parts the runtime updates have ids ending with the machine id
 * (`sensorFeedCBSystemModel`, `vgrArmRotationVGRSystemModel_01`, ...).
 */
export function generateSvg(layout: FactoryLayout, options: GenerateOptions = {}): string {
  return serialize(buildScene(layout, options)) + '\n';
}

/** Rulers along the top and left sides, with a tick every 100 mm. */
function rulers(xSize: number, ySize: number, withLabels: boolean): SvgNode {
  const line = (x1: number, y1: number, x2: number, y2: number) =>
    el('line', { x1, y1, x2, y2, stroke: 'black', 'stroke-width': 1 });
  const ticks: SvgNode[] = [line(0, 0, xSize, 0)];
  for (let x = 0; x < xSize; x += 100) {
    ticks.push(line(x, 0, x, 10));
    if (x > 0 && withLabels) ticks.push(el('text', { x: x + 2, y: 20, class: 'elementLabel' }, `${x / 10}cm`));
  }
  ticks.push(line(0, 0, 0, ySize));
  for (let y = 0; y < ySize; y += 100) {
    ticks.push(line(0, y, 10, y));
    if (y > 0 && withLabels) ticks.push(el('text', { x: 12, y: y + 4, class: 'elementLabel' }, `${y / 10}cm`));
  }
  return el('g', { id: 'rulers' }, ...ticks);
}

/**
 * "Not operational" popup of a machine, hidden until the runtime shows it: unrotated, just above the machine (below
 * it at the top of the map).
 */
function popup(element: LayoutElement, drawing: ElementDrawing, layout: FactoryLayout): SvgNode {
  const toMap = parseTransform(placement(element));
  const corners = [[0, 0], [drawing.width, 0], [0, drawing.length], [drawing.width, drawing.length]]
    .map(([x, y]) => applyMatrix(toMap, x, y));
  const minX = Math.min(...corners.map(c => c[0]));
  const minY = Math.min(...corners.map(c => c[1]));
  const maxY = Math.max(...corners.map(c => c[1]));

  const width = Math.max(200, element.name.length * 11 + 20);
  const height = 60;
  const x = Math.max(0, Math.min(minX, layout.xSize - width));
  const y = minY - height - 10 >= 0 ? minY - height - 10 : Math.min(maxY + 10, layout.ySize - height);
  return el('g', { id: `popup${element.id}`, class: 'machinePopup', transform: `translate(${x} ${y})` },
    el('rect', { x: 0, y: 0, width, height, class: 'machinePopupBox' }),
    el('text', { x: 10, y: 22, class: 'machinePopupText' }, element.name),
    el('text', { x: 10, y: 45, class: 'machinePopupTime' }, ''));
}
