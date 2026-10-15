package mbdo.dt.incident;

import mbdo.dt.data.DigitalShadow;
import mbdo.dt.data.DigitalShadow.IncidentRecord;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.replanning.ReplanningPlan;
import mbdo.dt.replanning.ReplanningPlan.Kind;
import mbdo.dt.replanning.ReplanningPlan.Status;
import mbdo.dt.replanning.ReplanningPlan.Step;
import mbdo.dt.replanning.ReplanningPlan.StepStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The incidents of the journal (the anomaly and recovery records of InfluxDB) are restored at startup, with their
 * replanning and recovery.
 */
class ReplanningIncidentJournalTest {

    private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

    private final DigitalShadow digitalShadow = mock(DigitalShadow.class);
    private final ReplanningIncidentRegistryService registry = new ReplanningIncidentRegistryService();
    private final ReplanningIncidentJournal journal = new ReplanningIncidentJournal(digitalShadow, registry,
            Duration.ofDays(7));

    private static AnomalyContext anomaly(String id, Instant at) {
        return new AnomalyContext(id, at, "TokenLocation", "TokenDroppedOffAtConveyorBeltFeed", "cb",
                "TokenDroppedOffAtConveyorBeltFeed", "TokenDroppedOffAtConveyorBeltFeed", "feed departure limit " + id);
    }

    private static ReplanningPlan plan(String strategy, Status status) {
        return new ReplanningPlan(strategy, "", null, status, List.of(new Step(Kind.STOP_MISSION,
                "REN_MISSION_01/CB01", null, "ConveyorBeltNominalMission", "MovingToSensor", null, null,
                StepStatus.DONE, "machine stopped")), List.of(), "done");
    }

    @Test
    void pastIncidentsAreRestoredNewestFirstWithTheirCorrectionReplanningAndRecovery() {
        when(digitalShadow.loadIncidents(any())).thenReturn(List.of(
                new IncidentRecord(anomaly("a-1", T0)
                        .withReplanningPlan(plan("cbFailure", Status.EXECUTED))
                        .withRecoveryPlan(plan("cbRepaired", Status.EXECUTED)), T0.plusSeconds(120), "belt repaired"),
                new IncidentRecord(anomaly("a-2", T0.plusSeconds(600))
                        .withReplanningPlan(plan("cbFailure", Status.FAILED)), null, null)));

        journal.restore();

        List<ReplanningIncidentResponse> history = registry.getHistory();
        assertEquals(2, history.size());
        ReplanningIncidentResponse never = history.get(0);
        assertEquals("feed departure limit a-2", never.getMessage());
        assertEquals(ReplanningIncidentStatus.RESOLVED, never.getStatus());
        assertTrue(never.getOperatorComment().startsWith("Not repaired"));
        assertEquals(Status.FAILED, never.getReplanningPlan().status());
        assertNull(never.getRecoveryPlan());

        ReplanningIncidentResponse repaired = history.get(1);
        assertEquals(T0, repaired.getStartTime());
        assertEquals(T0.plusSeconds(120), repaired.getResolvedAt());
        assertEquals("belt repaired", repaired.getOperatorComment());
        assertEquals("cbFailure", repaired.getReplanningPlan().strategy());
        assertEquals(StepStatus.DONE, repaired.getReplanningPlan().steps().get(0).status());
        assertEquals("cbRepaired", repaired.getRecoveryPlan().strategy());
    }

    @Test
    void incidentsOfThisRunStayNewerThanTheRestoredOnes() {
        ReplanningIncidentResponse live = registry.openIncident(anomaly("live", T0.plusSeconds(3600)));
        registry.resolveCurrentIncident(live.getId(), "ok");
        when(digitalShadow.loadIncidents(any())).thenReturn(List.of(
                new IncidentRecord(anomaly("old", T0), T0.plusSeconds(60), "fixed")));

        journal.restore();

        assertEquals(List.of("feed departure limit live", "feed departure limit old"),
                registry.getHistory().stream().map(ReplanningIncidentResponse::getMessage).toList());
    }

    @Test
    void theReplanningIsAddedToTheAnomalyRecordAndTheRecoveryToTheRecoveryRecord() {
        ReplanningIncidentResponse incident = registry.openIncident(anomaly("a-1", T0));
        ReplanningPlan replanning = plan("cbFailure", Status.EXECUTED);
        ReplanningPlan recovery = plan("cbRepaired", Status.EXECUTED);

        journal.recordReplanning("a-1", ReplanningIncidentJournal.REPLANNING, replanning);
        registry.resolveCurrentIncident(incident.getId(), "belt repaired", T0.plusSeconds(120));
        journal.recordReplanning("a-1", ReplanningIncidentJournal.RECOVERY, recovery);

        verify(digitalShadow).recordAnomaly(argThat(a -> a.anomalyId().equals("a-1")
                && a.detectionTimestamp().equals(T0) && a.replanningPlan() == replanning));
        verify(digitalShadow).recordRecovery(argThat(a -> a.recoveryPlan() == recovery), eq("belt repaired"),
                eq(T0.plusSeconds(120)));
    }

    @Test
    void anUnreachableInfluxDbLeavesTheHistoryEmpty() {
        when(digitalShadow.loadIncidents(any())).thenThrow(new RuntimeException("connection refused"));

        journal.restore();

        assertTrue(registry.getHistory().isEmpty());
    }
}
