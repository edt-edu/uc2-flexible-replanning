package mbdo.dt.telemetry;

import mbdo.dt.services.AnomalyDetection;
import mbdo.dt.telemetry.dto.TokenLocationResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Infers where the token is, and its color, from the state of the TokenLocation state machine run by
 * anomaly detection, and pushes every change to the shopfloor UI as a "token-update" SSE event.
 *
 * <p>The color is only known once the token reached a SortingLine color output: it is taken from the last
 * TokenAtSortingLine*Output state of the transition history, since the token entered the SortingLine.
 */
@Service
public class TokenLocationInferenceService {

    /** TokenLocation state -> shopfloor location of the token (null: no token on the line). */
    private static final Map<String, String> LOCATION_BY_STATE = Map.ofEntries(
            Map.entry("TokenAtSortingLineInput", "sl-input"),
            Map.entry("TokenAtSortingLineWhiteOutput", "sl-white"),
            Map.entry("TokenAtSortingLineRedOutput", "sl-red"),
            Map.entry("TokenAtSortingLineBlueOutput", "sl-blue"),
            Map.entry("TokenPickedUpByVGR1", "vgr1"),
            Map.entry("TokenDroppedOffAtConveyorBeltFeed", "cb-feed"),
            Map.entry("TokenAtConveyorBeltSwap", "cb-swap"),
            Map.entry("TokenPickedUpByVGR2", "vgr2"),
            Map.entry("TokenDroppedOffAtMPOInput", "mps-input"),
            Map.entry("TokenAtMPOOutput", "mps-output")
    );

    private static final Map<String, String> COLOR_BY_STATE = Map.of(
            "TokenAtSortingLineWhiteOutput", "WHITE",
            "TokenAtSortingLineRedOutput", "RED",
            "TokenAtSortingLineBlueOutput", "BLUE"
    );

    /** The state in which a new token enters the line: the color of the previous token no longer applies. */
    private static final String TOKEN_ENTRY_STATE = "TokenAtSortingLineInput";

    private final AnomalyDetection anomalyDetection;
    private final MachineTelemetrySseService sseService;

    private volatile TokenLocationResponse lastBroadcast;

    public TokenLocationInferenceService(AnomalyDetection anomalyDetection, MachineTelemetrySseService sseService) {
        this.anomalyDetection = anomalyDetection;
        this.sseService = sseService;
    }

    public TokenLocationResponse currentTokenLocation() {
        String state = anomalyDetection.getCurrentState();
        return new TokenLocationResponse(
                state,
                state == null ? null : LOCATION_BY_STATE.get(state),
                inferColor(anomalyDetection.getTransitionHistory()),
                anomalyDetection.isAnomalyDetected()
        );
    }

    @Scheduled(fixedDelayString = "${telemetry.token-location-poll-interval-ms:250}")
    public void broadcastOnChange() {
        TokenLocationResponse current = currentTokenLocation();
        if (!Objects.equals(current, lastBroadcast)) {
            lastBroadcast = current;
            sseService.broadcastTokenUpdate(current);
        }
    }

    static String inferColor(List<String> transitionHistory) {
        for (int i = transitionHistory.size() - 1; i >= 0; i--) {
            String state = transitionHistory.get(i);
            if (COLOR_BY_STATE.containsKey(state)) {
                return COLOR_BY_STATE.get(state);
            }
            if (TOKEN_ENTRY_STATE.equals(state)) {
                return null;
            }
        }
        return null;
    }
}
