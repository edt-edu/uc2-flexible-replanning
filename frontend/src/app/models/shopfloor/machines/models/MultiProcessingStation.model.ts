import { MachineTelemetryData } from '../interfaces/IMachine';
import { GenericMachine } from './GenericMachine.model';

export class MultiProcessingStationModel extends GenericMachine {

    sawEnabled: boolean = false;
    sawRotation: number = 0;
    sliderExtended: boolean = false;
    sensorIn: boolean = false;
    sensorOut: boolean = false;

    override update(data: MachineTelemetryData): void {
        super.update(data);
        const state = data.value as Record<string, unknown>;

        if (typeof state['sawEnabled'] === 'boolean') {
            this.sawEnabled = state['sawEnabled'];
        }
        if (typeof state['sawRotation'] === 'number') {
            this.sawRotation = state['sawRotation'];
        }
        if (typeof state['sliderExtended'] === 'boolean') {
            this.sliderExtended = state['sliderExtended'];
        }
        if (typeof state['sensorIn'] === 'boolean') {
            this.sensorIn = state['sensorIn'];
        }
        if (typeof state['sensorOut'] === 'boolean') {
            this.sensorOut = state['sensorOut'];
        }
    }

    override render(machineEl: SVGGElement): void {
        super.render(machineEl);

        this.toggleElementClass(machineEl, `sensorIn${this.id}`, 'active', this.sensorIn);
        this.toggleElementClass(machineEl, `sensorOut${this.id}`, 'active', this.sensorOut);

        const slider = machineEl.querySelector<SVGRectElement>(`#slider${this.id} rect`);
        if (slider) {
            slider.classList.toggle('active', this.sliderExtended);
        }

        const saw = machineEl.querySelector<SVGPolygonElement>(`#saw${this.id} polygon`);
        if (saw) {
            saw.classList.toggle('sawActive', this.sawEnabled);
            saw.setAttribute('transform', `rotate(${this.sawRotation} 156 180)`);
        }
    }
}
