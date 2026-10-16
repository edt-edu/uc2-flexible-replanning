import { asBoolean, asNumber } from '../attributes.js';
import { MachineModel } from './MachineModel.js';

/** Light barriers: state field (also the prefix of the SVG element id) and gateway alias. */
const BARRIERS = [
  ['sortingLineSensInputLightBarrier', 'sensorslin'],
  ['sortingLineSensMiddleLightBarrier', undefined],
  ['sortingLineSensWhiteLightBarrier', 'sensorslwhite'],
  ['sortingLineSensBlueLightBarrier', 'sensorslblue'],
  ['sortingLineSensRedLightBarrier', 'sensorslred'],
] as const;

type Barrier = typeof BARRIERS[number][0];

export class SortingLineModel extends MachineModel {
  readonly barriers: Record<Barrier, boolean> = {
    sortingLineSensInputLightBarrier: false,
    sortingLineSensMiddleLightBarrier: false,
    sortingLineSensWhiteLightBarrier: false,
    sortingLineSensBlueLightBarrier: false,
    sortingLineSensRedLightBarrier: false,
  };
  sortingLineSensImpulseCounterRaw = 0;

  protected applyKindAttribute(attribute: string, value: unknown): void {
    const bool = asBoolean(value);
    if (bool !== null) {
      const barrier = BARRIERS.find(([field, alias]) => attribute.includes(field.toLowerCase()) || attribute === alias);
      if (barrier) {
        // the light barriers are active-low (false = token in the beam)
        this.barriers[barrier[0]] = !bool;
        return;
      }
    }
    const number = asNumber(value);
    if (number !== null && attribute.includes('sortinglinesensimpulsecounterraw')) {
      this.sortingLineSensImpulseCounterRaw = number;
    }
  }

  protected kindState(): Record<string, unknown> {
    return { ...this.barriers, sortingLineSensImpulseCounterRaw: this.sortingLineSensImpulseCounterRaw };
  }

  protected renderKind(machineEl: SVGGElement): void {
    for (const [field] of BARRIERS) {
      this.toggleClass(machineEl, `${field}${this.id}`, 'active', this.barriers[field]);
    }
  }
}
