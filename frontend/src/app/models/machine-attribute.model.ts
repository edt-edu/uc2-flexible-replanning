/** Raw controller attribute of a machine, as pushed by the backend ("machine-attribute" SSE events). */
export interface MachineAttributeUpdate {
    /** Gateway machine model name, e.g. VGRSystemModel_01 (the layout's telemetryId or machine id) */
    machineId: string;
    /** Attribute name, e.g. vacuumSensRotEncoderCounter */
    attribute: string;
    /** Raw value: boolean, number, string or JSON object */
    value: unknown;
    timestamp: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

export function fromMachineAttributeUpdate(raw: unknown): MachineAttributeUpdate {
    const update = isRecord(raw) ? raw : {};
    const text = (value: unknown) => typeof value === 'string' ? value : '';

    return {
        machineId: text(update['machineId']).trim(),
        attribute: text(update['attribute']),
        value: update['value'],
        timestamp: text(update['timestamp']) || new Date().toISOString()
    };
}
