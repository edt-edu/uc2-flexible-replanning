import { asBoolean, asRecord, normalizeAttribute } from '../attributes.js';

/** Element of the machine group with this id. */
export function byId<T extends Element = SVGElement>(root: ParentNode, id: string): T | null {
  return root.querySelector<T>(`[id="${id}"]`);
}

/**
 * State of a machine, fed with raw controller attributes, and how it is drawn on its SVG group.
 *
 * Attribute names are matched once normalized (lower case, no separators), so both the PLC names
 * (`sortingLineSensInputLightBarrier`) and the gateway aliases (`sensor_SL_in`) are understood.
 */
export abstract class MachineModel {
  isOperational = true;
  /** No controller publishes an initialization event to every app: machines are considered initialized from the start. */
  isInitialized = true;
  /**
   * When the application found the machine failed (e.g. a replanning stopped it), else null: drawn as not operational.
   * Flexible-replanning adaptation, not in the library yet (see ../../README.md).
   */
  failedSince: Date | null = null;
  lastUpdate: Date | null = null;

  constructor(readonly id: string) { }

  /** Applies one raw controller attribute. */
  applyAttribute(attribute: string, value: unknown, timestamp?: Date): void {
    const normalized = normalizeAttribute(attribute);
    if (normalized.includes('isoperational')) {
      const operational = asBoolean(value);
      if (operational !== null) {
        this.isOperational = operational;
      }
    } else if (normalized.includes('machinefeedback')) {
      this.applyMachineFeedback(value);
    } else {
      this.applyKindAttribute(normalized, value);
    }
    if (timestamp) {
      this.lastUpdate = timestamp;
    }
  }

  /** Current state, as plain values (e.g. for an application panel). */
  state(): Record<string, unknown> {
    return { ...this.kindState(), isOperational: this.isOperational, isInitialized: this.isInitialized,
      failedSince: this.failedSince };
  }

  /** Not operational as the controller reports it, or failed as the application found it: both are drawn alike. */
  get isStopped(): boolean {
    return !this.isOperational || this.failedSince !== null;
  }

  /** Draws the state on the machine's SVG group. */
  render(machineEl: SVGGElement): void {
    machineEl.classList.toggle('machineNotOperational', this.isStopped);
    machineEl.classList.toggle('machineNotInitialized', !this.isStopped && !this.isInitialized);

    const popup = machineEl.ownerSVGElement ? byId<SVGGElement>(machineEl.ownerSVGElement, `popup${this.id}`) : null;
    if (popup) {
      popup.classList.toggle('machinePopupVisible', this.isStopped);
      const time = popup.querySelector('.machinePopupTime');
      if (time) {
        time.textContent = this.failedSince ? `Failed at: ${this.failedSince.toLocaleString()}`
          : this.isOperational ? '' : `Stopped at: ${(this.lastUpdate ?? new Date()).toLocaleString()}`;
      }
    }
    this.renderKind(machineEl);
  }

  protected abstract applyKindAttribute(normalizedAttribute: string, value: unknown): void;

  protected abstract kindState(): Record<string, unknown>;

  protected abstract renderKind(machineEl: SVGGElement): void;

  /** Called when the controller reports the machine initialized (`INITIALIZED_IDLE`). */
  protected onInitializedFromFeedback(): void { }

  /** Toggles `className` on the element `#{elementId}` of this machine. */
  protected toggleClass(machineEl: SVGGElement, elementId: string, className: string, force: boolean): void {
    byId(machineEl, elementId)?.classList.toggle(className, force);
  }

  private applyMachineFeedback(value: unknown): void {
    if (asRecord(value)?.['status'] === 'INITIALIZED_IDLE') {
      this.isInitialized = true;
      this.onInitializedFromFeedback();
    }
  }
}
