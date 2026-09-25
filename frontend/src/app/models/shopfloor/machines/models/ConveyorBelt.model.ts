import { MachineTelemetryData } from '../interfaces/IMachine';
import { GenericMachine } from './GenericMachine.model';

export class ConveyorBeltModel extends GenericMachine {

    direction: string = 'forward';
    isExecuting: boolean = false;
    sensorFeed: boolean = false;
    sensorSwap: boolean = false;
    sensorImpulse: boolean = false;

    override update(data: MachineTelemetryData): void {
        super.update(data);
        const state = data.value as Record<string, unknown>;

        if (typeof state['direction'] === 'string') {
            this.direction = state['direction'];
        }
        if (typeof state['isExecuting'] === 'boolean') {
            this.isExecuting = state['isExecuting'];
        }
        if (typeof state['sensorFeed'] === 'boolean') {
            this.sensorFeed = state['sensorFeed'];
        }
        if (typeof state['sensorSwap'] === 'boolean') {
            this.sensorSwap = state['sensorSwap'];
        }
        if (typeof state['sensorImpulse'] === 'boolean') {
            this.sensorImpulse = state['sensorImpulse'];
        }
    }

    private runningAnimation: SVGAnimationElement | null = null;

    override render(machineEl: SVGGElement): void {
        super.render(machineEl);
        this.toggleElementClass(machineEl, `beltSurface${this.id}`, 'movingPartNotOperational', !this.isOperational);
        this.toggleElementClass(machineEl, `sensorFeed${this.id}`, 'active', this.sensorFeed);
        this.toggleElementClass(machineEl, `sensorSwap${this.id}`, 'active', this.sensorSwap);
        this.renderBeltAnimation(machineEl);
    }

    /** Scrolls the belt pattern (SMIL animation in the SVG defs) while the belt is running. */
    private renderBeltAnimation(machineEl: SVGGElement): void {
        const svg = machineEl.ownerSVGElement;
        if (!svg) {
            return;
        }

        const isBackward = this.direction === 'backward' || this.direction === 'reverse';
        const animationId = isBackward ? `beltBackward${this.id}` : `beltForward${this.id}`;
        const target = this.isOperational && this.isExecuting
            ? svg.querySelector<SVGAnimationElement>(`#${animationId}`)
            : null;

        if (target === this.runningAnimation) {
            return;
        }
        this.runningAnimation?.endElement();
        target?.beginElement();
        this.runningAnimation = target;
    }
}