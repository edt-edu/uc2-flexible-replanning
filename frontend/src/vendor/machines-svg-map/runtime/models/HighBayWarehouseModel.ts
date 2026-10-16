import { asBoolean } from '../attributes.js';
import { byId, MachineModel } from './MachineModel.js';

export class HighBayWarehouseModel extends MachineModel {
  storageA = false;
  storageB = false;
  storageC = false;
  inputBarrier = false;
  outputBarrier = false;

  protected applyKindAttribute(attribute: string, value: unknown): void {
    const bool = asBoolean(value);
    if (bool === null) {
      return;
    }
    if (attribute.includes('storagea')) {
      this.storageA = bool;
    } else if (attribute.includes('storageb')) {
      this.storageB = bool;
    } else if (attribute.includes('storagec')) {
      this.storageC = bool;
    } else if (attribute.includes('inputbarrier')) {
      this.inputBarrier = bool;
    } else if (attribute.includes('outputbarrier')) {
      this.outputBarrier = bool;
    }
  }

  protected kindState(): Record<string, unknown> {
    const { storageA, storageB, storageC, inputBarrier, outputBarrier } = this;
    return { storageA, storageB, storageC, inputBarrier, outputBarrier };
  }

  protected renderKind(machineEl: SVGGElement): void {
    const storage = byId(machineEl, `storage${this.id}`);
    const states = [this.storageA, this.storageB, this.storageC];
    storage?.querySelectorAll('rect').forEach((rect, index) => rect.classList.toggle('active', states[index] ?? false));
    this.toggleClass(machineEl, `inputBarrier${this.id}`, 'active', this.inputBarrier);
    this.toggleClass(machineEl, `outputBarrier${this.id}`, 'active', this.outputBarrier);
  }
}
