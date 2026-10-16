package mbdo.dt.telemetry.dto;

import java.util.List;

/**
 * Token positions inferred from the TokenLocation state machine, one run per token on the line.
 *
 * @param state    current state of the most recent token (the initial state when no token is on the line)
 * @param location shopfloor location of the most recent token (sl-input, sl-blue, vgr1, cb-feed, ...), null when none
 * @param color    WHITE / RED / BLUE once the most recent token was sorted, null before
 * @param anomaly  whether an anomaly was detected on any token
 * @param tokens   every token on the line, the oldest first
 */
public record TokenLocationResponse(
        String state,
        String location,
        String color,
        boolean anomaly,
        List<Token> tokens
) {

    /**
     * One token on the line.
     *
     * @param number   its number, in the order the tokens entered the line
     * @param anomaly  whether an anomaly was detected on it (its location is then the last known one)
     * @param present  whether the sensor of its location still sees it; false: the location is only its last known
     *                 position (a trace), kept for a token with an anomaly until the incident is resolved
     */
    public record Token(int number, String state, String location, String color, boolean anomaly, boolean present) {
    }
}
