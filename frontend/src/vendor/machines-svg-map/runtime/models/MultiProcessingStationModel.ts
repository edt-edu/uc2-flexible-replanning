import { SAW_CENTER } from '../../machines/MultiProcessingStation.js';
import { asBoolean, asNumber } from '../attributes.js';
import { byId, MachineModel } from './MachineModel.js';

export class MultiProcessingStationModel extends MachineModel {
  sawEnabled = false;
  sawRotation = 0;
  sliderExtended = false;
  sensorIn = false;
  sensorOut = false;

  protected applyKindAttribute(attribute: string, value: unknown): void {
    const bool = asBoolean(value);
    if (bool !== null) {
      if (attribute.includes('sawenabled')) {
        this.sawEnabled = bool;
        return;
      }
      if (attribute.includes('sliderextended')) {
        this.sliderExtended = bool;
        return;
      }
      // PLC light barriers, active-low (false = token in the beam). The input is the oven feeder, where VGR2 places
      // the token.
      if (attribute === 'multiprocessingsensoven') {
        this.sensorIn = !bool;
        return;
      }
      if (attribute === 'multiprocessingsensendconveyor') {
        this.sensorOut = !bool;
        return;
      }
      // gateway aliases sensor_MPS_in / sensor_MPS_out, already active-high
      if (attribute === 'sensormpsin') {
        this.sensorIn = bool;
        return;
      }
      if (attribute === 'sensormpsout') {
        this.sensorOut = bool;
        return;
      }
    }
    const number = asNumber(value);
    if (number !== null && attribute.includes('sawrotation')) {
      this.sawRotation = number;
    }
  }

  protected kindState(): Record<string, unknown> {
    const { sawEnabled, sawRotation, sliderExtended, sensorIn, sensorOut } = this;
    return { sawEnabled, sawRotation, sliderExtended, sensorIn, sensorOut };
  }

  protected renderKind(machineEl: SVGGElement): void {
    this.toggleClass(machineEl, `sensorIn${this.id}`, 'active', this.sensorIn);
    this.toggleClass(machineEl, `sensorOut${this.id}`, 'active', this.sensorOut);
    byId(machineEl, `slider${this.id}`)?.querySelector('rect')?.classList.toggle('active', this.sliderExtended);

    const saw = byId(machineEl, `saw${this.id}`)?.querySelector('polygon');
    if (saw) {
      saw.classList.toggle('sawActive', this.sawEnabled);
      saw.setAttribute('transform', `rotate(${this.sawRotation} ${SAW_CENTER.x} ${SAW_CENTER.y})`);
    }
  }
}
