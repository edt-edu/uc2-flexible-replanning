package mbdo.dt.telemetry;

import mbdo.dt.services.AnomalyDetection;
import mbdo.dt.services.RuntimeContext;
import mbdo.dt.telemetry.dto.TokenLocationResponse;
import mbdo.dt.utils.RuntimeInterpreterUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Infers where the tokens are, and their color, from the runs of the TokenLocation state machine (one per token) of
 * the anomaly detection, and pushes every change to the shopfloor UI as a "token-update" SSE event.
 *
 * <p>The color is only known once the token reached a SortingLine color output: it is taken from the last
 * TokenAtSortingLine*Output state of the transition history, since the token entered the SortingLine.
 *
 * <p>A token is only shown at its location while the sensor of that location still sees it (light barrier broken,
 * gripper suction on). Once that sensor is deactivated, the location is only the token's last known position: it is
 * sent as a trace ({@code present == false}). A token whose run stopped on an anomaly stays a trace, even if another
 * token later activates the same sensor, until the operator resolves the incident: its run then ends and the token
 * disappears.
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

    /** A sensor of the TokenLocation model, and its value while it sees a token. */
    record PresenceSensor(String attribute, boolean activeValue) {
    }

    /** Shopfloor location -> the sensor that sees a token there (light barriers are active-low). */
    private static final Map<String, PresenceSensor> PRESENCE_SENSOR_BY_LOCATION = Map.ofEntries(
            Map.entry("sl-input", new PresenceSensor("sl.sensor_SL_in", false)),
            Map.entry("sl-white", new PresenceSensor("sl.sensor_SL_white", false)),
            Map.entry("sl-red", new PresenceSensor("sl.sensor_SL_red", false)),
            Map.entry("sl-blue", new PresenceSensor("sl.sensor_SL_blue", false)),
            Map.entry("vgr1", new PresenceSensor("vgr1.vacuumActValve", true)),
            Map.entry("cb-feed", new PresenceSensor("cb.conveyorSensFeed", false)),
            Map.entry("cb-swap", new PresenceSensor("cb.conveyorSensSwap", false)),
            Map.entry("vgr2", new PresenceSensor("vgr2.vacuumActValve", true)),
            Map.entry("mps-input", new PresenceSensor("mpo.sensor_MPS_in", false)),
            Map.entry("mps-output", new PresenceSensor("mpo.sensor_MPS_out", false))
    );

    private static final Map<String, String> COLOR_BY_STATE = Map.of(
            "TokenAtSortingLineWhiteOutput", "WHITE",
            "TokenAtSortingLineRedOutput", "RED",
            "TokenAtSortingLineBlueOutput", "BLUE"
    );

    /** The state in which a new token enters the line: the color of the previous token no longer applies. */
    private static final String TOKEN_ENTRY_STATE = "TokenAtSortingLineInput";

    private final AnomalyDetection anomalyDetection;
    private final RuntimeContext runtimeContext;
    private final MachineTelemetrySseService sseService;

    /** The anomalies whose token left its last known location: only a trace remains until the incident is resolved. */
    private final Set<String> anomaliesWithTokenGone = new HashSet<>();

    private volatile TokenLocationResponse lastBroadcast;

    public TokenLocationInferenceService(AnomalyDetection anomalyDetection, RuntimeContext runtimeContext,
            MachineTelemetrySseService sseService) {
        this.anomalyDetection = anomalyDetection;
        this.runtimeContext = runtimeContext;
        this.sseService = sseService;
    }

    public synchronized TokenLocationResponse currentTokenLocation() {
        List<AnomalyDetection.TokenRunState> runs = anomalyDetection.getTokens();
        Map<String, Object> observedValues = runtimeContext.snapshot();

        // resolved incidents: their token's run ended, forget it
        Set<String> openAnomalies = runs.stream()
                .map(AnomalyDetection.TokenRunState::anomalyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        anomaliesWithTokenGone.retainAll(openAnomalies);

        List<TokenLocationResponse.Token> tokens = runs.stream()
                .map(token -> {
                    String location = LOCATION_BY_STATE.get(token.state());
                    return new TokenLocationResponse.Token(
                            token.token(),
                            token.state(),
                            location,
                            inferColor(token.transitionHistory()),
                            token.anomaly(),
                            present(token, location, observedValues));
                })
                .toList();
        String state = anomalyDetection.getCurrentState();
        return new TokenLocationResponse(
                state,
                state == null ? null : LOCATION_BY_STATE.get(state),
                inferColor(anomalyDetection.getTransitionHistory()),
                anomalyDetection.isAnomalyDetected(),
                tokens
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

    /** Whether the token is still seen at its location; once a token with an anomaly is gone, it stays gone. */
    private boolean present(AnomalyDetection.TokenRunState token, String location, Map<String, Object> observedValues) {
        String anomalyId = token.anomalyId();
        if (anomalyId != null && anomaliesWithTokenGone.contains(anomalyId)) {
            return false;
        }
        boolean present = sensorSeesToken(location, observedValues);
        if (!present && anomalyId != null) {
            anomaliesWithTokenGone.add(anomalyId);
        }
        return present;
    }

    /** Whether the sensor of the location sees a token; true when it is unknown (no sensor, or no value yet). */
    static boolean sensorSeesToken(String location, Map<String, ?> observedValues) {
        PresenceSensor sensor = location == null ? null : PRESENCE_SENSOR_BY_LOCATION.get(location);
        if (sensor == null) {
            return true;
        }
        Object value = observedValues.get(sensor.attribute());
        return value == null || RuntimeInterpreterUtils.valuesMatch(sensor.activeValue(), value);
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
