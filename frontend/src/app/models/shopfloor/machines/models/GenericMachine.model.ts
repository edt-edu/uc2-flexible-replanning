import { IMachine, MachineTelemetryData } from '../interfaces/IMachine';

export class GenericMachine implements IMachine {
    id: string;
    position: { x: number; y: number };
    rotation: number;
    isOperational: boolean = true;
    isInitialized: boolean = false;
    failedSince: Date | null = null;
    lastUpdateTimestamp: Date | null = null;

    constructor(id: string, position: { x: number; y: number }, rotation: number) {
        this.id = id;
        this.position = position;
        this.rotation = rotation;
        this.isInitialized = true;
    }

    update(data: MachineTelemetryData): void {
        if (data.attribute !== 'state' || typeof data.value !== 'object' || data.value === null) {
            return;
        }

        const state = data.value as Record<string, unknown>;
        this.lastUpdateTimestamp = new Date(data.timestamp);

        if (typeof state['isOperational'] === 'boolean') {
            this.isOperational = state['isOperational'];
        }
        if (typeof state['isInitialized'] === 'boolean') {
            this.isInitialized = state['isInitialized'];
        }

    }

    /** Not operational as the PLC reports it, or failed as the digital twin found it: both are shown alike. */
    protected get isStopped(): boolean {
        return !this.isOperational || this.failedSince !== null;
    }

    render(machineEl: SVGGElement): void {
        const woodBase = machineEl.querySelector<SVGRectElement>(`#woodBase${this.id} rect`);
        if (woodBase) {
            if (this.isStopped) {
                woodBase.setAttribute('fill', 'red');
            } else if (!this.isInitialized) {
                woodBase.setAttribute('fill', '#fef3c7');
            } else {
                woodBase.setAttribute('fill', 'burlywood');
            }
        }

        this.renderMachinePopup(machineEl);
    }

    /** Toggles `className` on the element `#{elementId}` inside this machine's group. */
    protected toggleElementClass(machineEl: SVGGElement, elementId: string, className: string, force: boolean): void {
        machineEl.querySelector(`#${elementId}`)?.classList.toggle(className, force);
    }

    private renderMachinePopup(machineEl: SVGGElement): void {
        const popup = machineEl.ownerSVGElement?.querySelector<SVGGElement>(`#popup${this.id}`) ?? null;
        if (popup) {
            popup.classList.toggle("machinePopupVisible", this.isStopped);
            this.lastUpdateTimestamp = this.lastUpdateTimestamp ?? new Date();
            const timeText = popup.querySelector<SVGTextElement>('.machinePopupTime');
            if (timeText) {
                timeText.textContent = this.failedSince
                    ? `Failed at: ${this.failedSince.toLocaleString()}`
                    : `Stopped at: ${this.lastUpdateTimestamp.toLocaleString()}`;
            }
        }

        machineEl.classList.toggle("machineNotOperational", this.isStopped);
        machineEl.classList.toggle("machineNotInitialized", !this.isStopped && !this.isInitialized);
    }
}
