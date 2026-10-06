package mbdo.dt.telemetry.dto;

/**
 * Token position inferred from the TokenLocation state machine.
 *
 * @param state    current state of the state machine
 * @param location shopfloor location of the token (sl-input, sl-blue, vgr1, cb-feed, ...), null when no token
 * @param color    WHITE / RED / BLUE once the token was sorted, null before
 * @param anomaly  whether anomaly detection stopped on an anomaly (the location is then the last known one)
 */
public record TokenLocationResponse(
        String state,
        String location,
        String color,
        boolean anomaly
) {
}
