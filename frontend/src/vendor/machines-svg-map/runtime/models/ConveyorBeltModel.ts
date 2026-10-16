import { asBoolean } from '../attributes.js';
import { byId, MachineModel } from './MachineModel.js';

export class ConveyorBeltModel extends MachineModel {
  direction = 'forward';
  isExecuting = false;
  sensorFeed = false;
  sensorSwap = false;
  sensorImpulse = false;

  private runningAnimation: SVGAnimationElement | null = null;

  protected applyKindAttribute(attribute: string, value: unknown): void {
    if (attribute.includes('direction') && value != null) {
      this.direction = String(value);
      return;
    }
    const bool = asBoolean(value);
    if (bool === null) {
      return;
    }
    if (attribute.includes('isexecut')) {
      this.isExecuting = bool;
    } else if (attribute.includes('sensorfeed') || attribute.includes('sensfeed')) {
      // the feed / swap light barriers are active-low (false = token in the beam)
      this.sensorFeed = !bool;
    } else if (attribute.includes('sensorswap') || attribute.includes('sensswap')) {
      this.sensorSwap = !bool;
    } else if (attribute.includes('sensorimpulse') || attribute.includes('sensimpulse')) {
      this.sensorImpulse = bool;
    }
  }

  protected kindState(): Record<string, unknown> {
    const { direction, isExecuting, sensorFeed, sensorSwap, sensorImpulse } = this;
    return { direction, isExecuting, sensorFeed, sensorSwap, sensorImpulse };
  }

  protected renderKind(machineEl: SVGGElement): void {
    this.toggleClass(machineEl, `beltSurface${this.id}`, 'movingPartNotOperational', this.isStopped);
    this.toggleClass(machineEl, `sensorFeed${this.id}`, 'active', this.sensorFeed);
    this.toggleClass(machineEl, `sensorSwap${this.id}`, 'active', this.sensorSwap);
    this.renderBeltAnimation(machineEl);
  }

  /** Scrolls the belt pattern (SMIL animation of the SVG defs) while the belt runs. */
  private renderBeltAnimation(machineEl: SVGGElement): void {
    const svg = machineEl.ownerSVGElement;
    if (!svg) {
      return;
    }
    const backward = this.direction === 'backward' || this.direction === 'reverse';
    const target = !this.isStopped && this.isExecuting
      ? byId<SVGAnimationElement>(svg, backward ? `beltBackward${this.id}` : `beltForward${this.id}`)
      : null;
    if (target === this.runningAnimation) {
      return;
    }
    this.runningAnimation?.endElement?.();
    target?.beginElement?.();
    this.runningAnimation = target;
  }
}
