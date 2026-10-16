package mbdo.dt.orchestrator;

import mbdo.dt.connectivity.GatewayService;
import mbdo.dt.data.DigitalShadow;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentResponse;
import mbdo.dt.models.SysMLV2Parser;
import mbdo.dt.services.AnomalyDetectionService;
import mbdo.dt.services.AnomalyDetectionService.AnomalyEvaluationResult;
import mbdo.dt.services.AnomalyDetectionService.EvaluationStatus;
import mbdo.dt.services.NotificationService;
import mbdo.dt.services.ReplanningService;
import mbdo.dt.services.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The engine reacts to the disruptions the detection reports, and to the operator's correction. */
class DtEngineTest {

    private final AnomalyDetectionService detection = mock(AnomalyDetectionService.class);
    private final ReplanningService replanning = mock(ReplanningService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RuntimeContext runtimeContext = mock(RuntimeContext.class);
    private final DtEngine engine = new DtEngine(mock(GatewayService.class), mock(DigitalShadow.class), detection,
            replanning, notifications, mock(SysMLV2Parser.class), new DefaultResourceLoader(), runtimeContext);

    private final AnomalyContext anomaly = new AnomalyContext("a-1", Instant.parse("2026-10-15T08:00:00Z"),
            "TokenLocation", "TokenDroppedOffAtConveyorBeltFeed", "cb", "TokenDroppedOffAtConveyorBeltFeed",
            "TokenDroppedOffAtConveyorBeltFeed", "feed departure limit");
    private final AnomalyEvaluationResult disruption = new AnomalyEvaluationResult(EvaluationStatus.ANOMALY_DETECTED,
            "TokenLocation", "TokenLocation", "TokenDroppedOffAtConveyorBeltFeed", "TokenDroppedOffAtConveyorBeltFeed",
            "ConveyorBeltFeedDepartureLimit", "feed departure limit", anomaly);

    @Test
    void aDisruptionDetectedByATimeCheckOpensItsIncidentThenIsReplannedOnTheLatestObservations() {
        Map<String, Object> latest = Map.of("cb.conveyorSensFeed", false);
        when(runtimeContext.snapshot()).thenReturn(latest);
        when(detection.detectDisruptions()).thenReturn(List.of(disruption));

        engine.checkTimeTransitions();

        InOrder order = inOrder(detection, notifications, replanning);
        order.verify(detection).checkTimeTransitions();
        order.verify(notifications).notifyDisruption(anomaly);
        order.verify(replanning).handleDisruption(disruption, latest);
    }

    @Test
    void theOperatorsCorrectionClosesTheIncidentReleasesTheTokenAndRecoversTheMachines() {
        ReplanningIncidentResponse closed = mock(ReplanningIncidentResponse.class);
        when(notifications.closeDisruption(anomaly, "belt repaired")).thenReturn(Optional.of(closed));

        assertTrue(engine.resolveDisruption(anomaly, "belt repaired").isPresent());

        verify(detection).releaseAnomaly("a-1");
        verify(replanning).recover("a-1");
    }

    @Test
    void anIncidentThatIsNotOpenIsNeitherReleasedNorRecovered() {
        when(notifications.closeDisruption(any(), any())).thenReturn(Optional.empty());

        assertTrue(engine.resolveDisruption(anomaly, "again").isEmpty());

        verify(detection, never()).releaseAnomaly(any());
        verify(replanning, never()).recover(any());
    }
}
