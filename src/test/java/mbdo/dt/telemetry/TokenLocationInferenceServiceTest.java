package mbdo.dt.telemetry;

import mbdo.dt.services.AnomalyDetectionService;
import mbdo.dt.services.AnomalyDetectionService.TokenRunState;
import mbdo.dt.services.RuntimeContext;
import mbdo.dt.telemetry.dto.TokenLocationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenLocationInferenceServiceTest {

    private static final List<String> AT_CB_FEED = List.of("WaitingForTokenAtSortingLineInput",
            "TokenAtSortingLineInput", "TokenAtSortingLineBlueOutput", "TokenPickedUpByVGR1",
            "TokenDroppedOffAtConveyorBeltFeed");

    private AnomalyDetectionService anomalyDetection;
    private final Map<String, Object> observedValues = new HashMap<>();
    private TokenLocationInferenceService service;

    @BeforeEach
    void setUp() {
        anomalyDetection = mock(AnomalyDetectionService.class);
        RuntimeContext runtimeContext = mock(RuntimeContext.class);
        when(runtimeContext.snapshot()).thenAnswer(invocation -> Map.copyOf(observedValues));
        service = new TokenLocationInferenceService(anomalyDetection, runtimeContext,
                mock(MachineTelemetrySseService.class));
    }

    private void tokens(TokenRunState... runs) {
        when(anomalyDetection.getTokens()).thenReturn(List.of(runs));
    }

    private TokenLocationResponse.Token onlyToken() {
        List<TokenLocationResponse.Token> tokens = service.currentTokenLocation().tokens();
        assertEquals(1, tokens.size());
        return tokens.get(0);
    }

    @Test
    void colorIsUnknownUntilTheTokenReachedAColorOutput() {
        assertNull(TokenLocationInferenceService.inferColor(
                List.of("WaitingForTokenAtSortingLineInput", "TokenAtSortingLineInput")));
    }

    @Test
    void colorComesFromTheColorOutputAndFollowsTheToken() {
        assertEquals("RED", TokenLocationInferenceService.inferColor(List.of(
                "WaitingForTokenAtSortingLineInput", "TokenAtSortingLineInput", "TokenAtSortingLineRedOutput",
                "TokenPickedUpByVGR1", "TokenDroppedOffAtConveyorBeltFeed")));
    }

    @Test
    void aNewTokenForgetsThePreviousColor() {
        assertNull(TokenLocationInferenceService.inferColor(List.of(
                "TokenAtSortingLineInput", "TokenAtSortingLineBlueOutput", "TokenAtMPOOutput",
                "WaitingForTokenAtSortingLineInput", "TokenAtSortingLineInput")));
    }

    @Test
    void sensorSeesTheTokenWhileTheLightBarrierIsBrokenOrTheSuctionOn() {
        assertTrue(TokenLocationInferenceService.sensorSeesToken("cb-feed", Map.of("cb.conveyorSensFeed", false)));
        assertFalse(TokenLocationInferenceService.sensorSeesToken("cb-feed", Map.of("cb.conveyorSensFeed", true)));
        assertTrue(TokenLocationInferenceService.sensorSeesToken("vgr1", Map.of("vgr1.vacuumActValve", "true")));
        assertFalse(TokenLocationInferenceService.sensorSeesToken("vgr1", Map.of("vgr1.vacuumActValve", false)));
    }

    @Test
    void anUnknownSensorValueKeepsTheTokenAtItsLocation() {
        assertTrue(TokenLocationInferenceService.sensorSeesToken("cb-feed", Map.of()));
        assertTrue(TokenLocationInferenceService.sensorSeesToken(null, Map.of()));
    }

    @Test
    void aTokenIsATraceOnceItsSensorIsDeactivated() {
        tokens(new TokenRunState(1, "TokenDroppedOffAtConveyorBeltFeed", AT_CB_FEED, false, null));

        observedValues.put("cb.conveyorSensFeed", false);
        assertTrue(onlyToken().present());

        observedValues.put("cb.conveyorSensFeed", true);
        TokenLocationResponse.Token trace = onlyToken();
        assertFalse(trace.present());
        assertEquals("cb-feed", trace.location());
    }

    @Test
    void aTokenWithAnAnomalyStaysATraceUntilTheIncidentIsResolved() {
        TokenRunState anomaly = new TokenRunState(1, "TokenDroppedOffAtConveyorBeltFeed", AT_CB_FEED, true, "a-1");
        tokens(anomaly);
        observedValues.put("cb.conveyorSensFeed", true);
        assertFalse(onlyToken().present());

        // another token now breaks the same light barrier: the token with the anomaly is still gone from there
        observedValues.put("cb.conveyorSensFeed", false);
        assertFalse(onlyToken().present());

        // the incident is resolved: the run ends, the token is cleared
        tokens();
        assertTrue(service.currentTokenLocation().tokens().isEmpty());
    }
}
