/** Token position inferred by the backend from the TokenLocation state machine (anomaly detection). */
export interface TokenLocation {
    /** Current state of the TokenLocation state machine */
    state: string | null;
    /** sl-input, sl-white, sl-red, sl-blue, vgr1, cb-feed, cb-swap, vgr2, mps-input, mps-output; null when no token */
    location: string | null;
    /** WHITE, RED or BLUE once the token was sorted, null before */
    color: 'WHITE' | 'RED' | 'BLUE' | null;
    /** Anomaly detection stopped on an anomaly: the location is the last known one */
    anomaly: boolean;
}

export function fromTokenLocation(raw: unknown): TokenLocation {
    const value = (raw ?? {}) as Record<string, unknown>;
    const color = value['color'];
    return {
        state: typeof value['state'] === 'string' ? value['state'] : null,
        location: typeof value['location'] === 'string' ? value['location'] : null,
        color: color === 'WHITE' || color === 'RED' || color === 'BLUE' ? color : null,
        anomaly: value['anomaly'] === true
    };
}
