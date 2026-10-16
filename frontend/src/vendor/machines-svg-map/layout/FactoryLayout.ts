/**
 * JSON configuration of a factory layout: where each machine, component and table is, in mm (1 SVG px = 1 mm).
 *
 * Each machine kind is drawn with all its parts (sensors, actuators), the same whatever the setup: the configuration
 * only places the machines and says which telemetry they are fed from.
 */

export const MACHINE_KINDS = ['VGR', 'MPO', 'CB', 'SLC', 'HBW'] as const;
export const COMPONENT_KINDS = ['S_SLI', 'RH_SLI'] as const;
export const CUSTOM_SIZE_COMPONENT_KINDS = ['TABLE'] as const;

export type MachineKind = typeof MACHINE_KINDS[number];
export type ComponentKind = typeof COMPONENT_KINDS[number];
export type CustomSizeComponentKind = typeof CUSTOM_SIZE_COMPONENT_KINDS[number];

export type ElementType = 'MACHINE' | 'COMPONENT' | 'CUSTOMSIZECOMPONENT';

interface BasePosition {
  /** Id of the element's SVG group, and suffix of the ids of its parts (`sensorFeed<id>`, ...). */
  id: string;
  /** Display name, shown as tooltip and in the "not operational" popup. */
  name: string;
  /** Reference point of the element (upper left corner of its wood base, sticker readable), in mm. */
  x: number;
  y: number;
  /** Rotation around the reference point, clockwise. */
  degrees: number;
  /** Optional: implied by `kind`, checked against it when given. */
  type?: ElementType;
}

/**
 * Named position of a VGR (as sent by the SCADA missions) used to draw its arm: at these encoder counts the end
 * effector is drawn on the `target` SVG element. The arm angle and length in between are interpolated.
 */
export interface ArmAnchor {
  name: string;
  rotationCounts: number;
  horizontalCounts: number;
  /** Id of the SVG element the end effector is on at this position, e.g. `sensorSwapCBSystemModel`. */
  target: string;
}

export interface MachinePosition extends BasePosition {
  kind: MachineKind;
  /** Machine id of the telemetry (gateway machine model name) when it differs from `id`. */
  telemetryId?: string;
  /** VGR only: named positions to draw the arm from the encoder counts. */
  armCalibration?: ArmAnchor[];
}

export interface ComponentPosition extends BasePosition {
  kind: ComponentKind;
}

export interface CustomSizeComponentPosition extends BasePosition {
  kind: CustomSizeComponentKind;
  width: number;
  length: number;
}

export type LayoutElement = MachinePosition | ComponentPosition | CustomSizeComponentPosition;

export interface FactoryLayout {
  name: string;
  xSize: number;
  ySize: number;
  positions: LayoutElement[];
  /** Token location (as reported by the application) -> id of the SVG element the token is drawn on. */
  tokenLocations?: Record<string, string>;
}

export function isMachine(element: LayoutElement): element is MachinePosition {
  return (MACHINE_KINDS as readonly string[]).includes(element.kind);
}

export function isCustomSizeComponent(element: LayoutElement): element is CustomSizeComponentPosition {
  return (CUSTOM_SIZE_COMPONENT_KINDS as readonly string[]).includes(element.kind);
}

export function elementTypeOf(kind: string): ElementType | undefined {
  if ((MACHINE_KINDS as readonly string[]).includes(kind)) return 'MACHINE';
  if ((COMPONENT_KINDS as readonly string[]).includes(kind)) return 'COMPONENT';
  if ((CUSTOM_SIZE_COMPONENT_KINDS as readonly string[]).includes(kind)) return 'CUSTOMSIZECOMPONENT';
  return undefined;
}

export class LayoutValidationError extends Error {
  constructor(readonly errors: string[], source?: string) {
    super(`Invalid factory layout${source ? ` ${source}` : ''}:\n  ${errors.join('\n  ')}`);
    this.name = 'LayoutValidationError';
  }
}

export interface ParsedLayout {
  layout: FactoryLayout;
  /** Problems the SVG can still be generated with, e.g. duplicate ids (only the first element gets the updates). */
  warnings: string[];
}

const BASE_KEYS = ['id', 'name', 'x', 'y', 'degrees', 'type', 'kind'];
const KEYS_BY_TYPE: Record<ElementType, string[]> = {
  MACHINE: [...BASE_KEYS, 'telemetryId', 'armCalibration'],
  COMPONENT: BASE_KEYS,
  CUSTOMSIZECOMPONENT: [...BASE_KEYS, 'width', 'length'],
};

/**
 * Checks a parsed JSON value against the layout schema (unknown properties are rejected) and returns it typed.
 * Throws a {@link LayoutValidationError} listing every problem.
 */
export function parseLayout(raw: unknown, source?: string): ParsedLayout {
  const errors: string[] = [];
  const warnings: string[] = [];
  const isRecord = (value: unknown): value is Record<string, unknown> =>
    typeof value === 'object' && value !== null && !Array.isArray(value);
  const check = (condition: boolean, message: string) => {
    if (!condition) errors.push(message);
  };
  const isNumber = (value: unknown) => typeof value === 'number' && Number.isFinite(value);
  const isString = (value: unknown) => typeof value === 'string' && value.length > 0;

  if (!isRecord(raw)) {
    throw new LayoutValidationError(['the layout must be an object'], source);
  }
  check(isString(raw['name']), 'name: must be a non-empty string');
  check(isNumber(raw['xSize']) && (raw['xSize'] as number) > 0, 'xSize: must be a positive number');
  check(isNumber(raw['ySize']) && (raw['ySize'] as number) > 0, 'ySize: must be a positive number');
  for (const key of Object.keys(raw)) {
    check(['name', 'xSize', 'ySize', 'positions', 'tokenLocations'].includes(key), `${key}: unknown property`);
  }

  const positions = raw['positions'];
  check(Array.isArray(positions), 'positions: must be an array');
  const ids = new Set<string>();
  (Array.isArray(positions) ? positions : []).forEach((position, index) => {
    const at = `positions[${index}]`;
    if (!isRecord(position)) {
      errors.push(`${at}: must be an object`);
      return;
    }
    const where = isString(position['id']) ? `${at} (${position['id']})` : at;
    const elementType = elementTypeOf(String(position['kind']));
    if (!elementType) {
      errors.push(`${where}.kind: must be one of ${[...MACHINE_KINDS, ...COMPONENT_KINDS, ...CUSTOM_SIZE_COMPONENT_KINDS].join(', ')}`);
      return;
    }
    check(position['type'] === undefined || position['type'] === elementType,
      `${where}.type: kind ${position['kind']} is a ${elementType}, not a ${position['type']}`);
    check(isString(position['id']), `${where}.id: must be a non-empty string`);
    check(isString(position['name']), `${where}.name: must be a non-empty string`);
    for (const key of ['x', 'y', 'degrees']) {
      check(isNumber(position[key]), `${where}.${key}: must be a number`);
    }
    for (const key of Object.keys(position)) {
      check(KEYS_BY_TYPE[elementType].includes(key), `${where}.${key}: unknown property for a ${elementType}`);
    }
    if (elementType === 'CUSTOMSIZECOMPONENT') {
      check(isNumber(position['width']), `${where}.width: must be a number`);
      check(isNumber(position['length']), `${where}.length: must be a number`);
    }
    if (position['telemetryId'] !== undefined) {
      check(isString(position['telemetryId']), `${where}.telemetryId: must be a non-empty string`);
    }
    if (position['armCalibration'] !== undefined) {
      const anchors = position['armCalibration'];
      check(position['kind'] === 'VGR', `${where}.armCalibration: only for a VGR`);
      if (!Array.isArray(anchors) || anchors.length === 0) {
        errors.push(`${where}.armCalibration: must be a non-empty array`);
      } else {
        anchors.forEach((anchor, anchorIndex) => {
          const anchorAt = `${where}.armCalibration[${anchorIndex}]`;
          if (!isRecord(anchor)) {
            errors.push(`${anchorAt}: must be an object`);
            return;
          }
          check(isString(anchor['name']), `${anchorAt}.name: must be a non-empty string`);
          check(isNumber(anchor['rotationCounts']), `${anchorAt}.rotationCounts: must be a number`);
          check(isNumber(anchor['horizontalCounts']), `${anchorAt}.horizontalCounts: must be a number`);
          check(isString(anchor['target']), `${anchorAt}.target: must be the id of an SVG element`);
          for (const key of Object.keys(anchor)) {
            check(['name', 'rotationCounts', 'horizontalCounts', 'target'].includes(key), `${anchorAt}.${key}: unknown property`);
          }
        });
      }
    }
    if (isString(position['id'])) {
      const id = position['id'] as string;
      if (ids.has(id)) {
        warnings.push(`${where}: duplicate id '${id}'`);
      }
      ids.add(id);
    }
  });

  const tokenLocations = raw['tokenLocations'];
  if (tokenLocations !== undefined) {
    if (!isRecord(tokenLocations)) {
      errors.push('tokenLocations: must be an object mapping token locations to SVG element ids');
    } else {
      for (const [location, elementId] of Object.entries(tokenLocations)) {
        check(isString(elementId), `tokenLocations.${location}: must be the id of an SVG element`);
      }
    }
  }

  if (errors.length > 0) {
    throw new LayoutValidationError(errors, source);
  }
  return { layout: raw as unknown as FactoryLayout, warnings };
}
