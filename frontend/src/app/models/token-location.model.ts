/** Token positions inferred by the backend from the TokenLocation state machine (one run per token on the line). */
export interface TokenLocation {
    /** Current state of the most recent token's run */
    state: string | null;
    /** sl-input, sl-white, sl-red, sl-blue, vgr1, cb-feed, cb-swap, vgr2, mps-input, mps-output; null when no token */
    location: string | null;
    /** WHITE, RED or BLUE once the token was sorted, null before */
    color: 'WHITE' | 'RED' | 'BLUE' | null;
    /** An anomaly was detected on a token */
    anomaly: boolean;
    /** Every token on the line, the oldest first */
    tokens: TokenOnLine[];
}

/** One token on the line. */
export interface TokenOnLine {
    /** Its number, in the order the tokens entered the line */
    number: number;
    state: string | null;
    location: string | null;
    color: 'WHITE' | 'RED' | 'BLUE' | null;
    /** An anomaly was detected on it: the location is the last known one */
    anomaly: boolean;
    /**
     * The sensor of its location still sees it. False: the location is only its last known position (a trace), kept
     * for a token with an anomaly until the incident is resolved
     */
    present: boolean;
}

function toColor(color: unknown): 'WHITE' | 'RED' | 'BLUE' | null {
    return color === 'WHITE' || color === 'RED' || color === 'BLUE' ? color : null;
}

function fromTokenOnLine(raw: unknown): TokenOnLine {
    const value = (raw ?? {}) as Record<string, unknown>;
    return {
        number: typeof value['number'] === 'number' ? value['number'] : 0,
        state: typeof value['state'] === 'string' ? value['state'] : null,
        location: typeof value['location'] === 'string' ? value['location'] : null,
        color: toColor(value['color']),
        anomaly: value['anomaly'] === true,
        present: value['present'] !== false
    };
}

export function fromTokenLocation(raw: unknown): TokenLocation {
    const value = (raw ?? {}) as Record<string, unknown>;
    return {
        state: typeof value['state'] === 'string' ? value['state'] : null,
        location: typeof value['location'] === 'string' ? value['location'] : null,
        color: toColor(value['color']),
        anomaly: value['anomaly'] === true,
        tokens: Array.isArray(value['tokens']) ? value['tokens'].map(fromTokenOnLine) : []
    };
}
