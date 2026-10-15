import { MachineStateUpdate } from '../../../machine-update.model';

export interface MachineTelemetryData {
    attribute: string;
    value: unknown;
    timestamp: string;
    rawUpdate?: MachineStateUpdate;
}

export interface IMachine {
    id: string;
    position: { x: number; y: number };
    rotation: number;
    isOperational: boolean;
    /** When the digital twin found the machine failed (a replanning incident stops it), else null. */
    failedSince: Date | null;
    lastUpdateTimestamp: Date | null;

    update(data: MachineTelemetryData): void;
    render(machineEl: SVGGElement): void;
}