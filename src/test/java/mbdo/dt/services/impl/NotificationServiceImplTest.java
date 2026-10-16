package mbdo.dt.services.impl;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentJournal;
import mbdo.dt.incident.ReplanningIncidentRegistryService;
import mbdo.dt.incident.ReplanningIncidentResponse;
import mbdo.dt.incident.ReplanningIncidentSseService;
import mbdo.dt.incident.ReplanningIncidentStatus;
import mbdo.dt.replanning.ReplanningPlan;
import mbdo.dt.replanning.ReplanningPlan.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The lifecycle of an incident: opened by its disruption, showing its replanning, closed by the operator, then showing
 * its recovery. A plan is journaled once it ended.
 */
class NotificationServiceImplTest {

    private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

    private final ReplanningIncidentRegistryService registry = new ReplanningIncidentRegistryService();
    private final ReplanningIncidentSseService sse = mock(ReplanningIncidentSseService.class);
    private final ReplanningIncidentJournal journal = mock(ReplanningIncidentJournal.class);
    private final NotificationServiceImpl notifications = new NotificationServiceImpl(registry, sse, journal);
    private final AnomalyContext anomaly = new AnomalyContext("a-1", T0, "TokenLocation",
            "TokenDroppedOffAtConveyorBeltFeed", "cb", "TokenDroppedOffAtConveyorBeltFeed",
            "TokenDroppedOffAtConveyorBeltFeed", "feed departure limit a-1");

    private static ReplanningPlan plan(String strategy, Status status) {
        return new ReplanningPlan(strategy, "", "a-1", status, List.of(), List.of(), null);
    }

    @Test
    void aDisruptionIsJournaledAndOpensItsIncident() {
        assertTrue(notifications.notifyDisruption(anomaly));

        verify(journal).recordAnomaly(anomaly);
        ReplanningIncidentResponse incident = registry.getCurrentIncident().orElseThrow();
        assertEquals(ReplanningIncidentStatus.OPEN, incident.getStatus());
        verify(sse).broadcastOpened(incident);
    }

    @Test
    void theReplanningIsShownInTheOpenIncidentAndJournaledOnceItEnded() {
        notifications.notifyDisruption(anomaly);
        AnomalyContext planned = anomaly.withReplanningPlan(plan("cbFailure", Status.PLANNED));
        AnomalyContext executed = anomaly.withReplanningPlan(plan("cbFailure", Status.EXECUTED));

        assertTrue(notifications.notifyDisruption(planned));
        assertEquals(planned.replanningPlan(), registry.getCurrentIncident().orElseThrow().getReplanningPlan());
        verify(journal, never()).recordAnomaly(planned);

        assertTrue(notifications.notifyDisruption(executed));
        verify(journal).recordAnomaly(executed);
    }

    @Test
    void aPlanForAClosedIncidentDropsTheReplanningAndIsJournaledAsItsLastOne() {
        notifications.notifyDisruption(anomaly);
        notifications.closeDisruption(anomaly, "belt repaired");
        AnomalyContext waiting = anomaly.withReplanningPlan(plan("cbFailure", Status.WAITING));

        assertFalse(notifications.notifyDisruption(waiting));
        verify(journal).recordAnomaly(waiting);
    }

    @Test
    void theOperatorClosesTheIncidentAndTheCorrectionIsJournaledAtItsResolutionTime() {
        notifications.notifyDisruption(anomaly);

        ReplanningIncidentResponse closed = notifications.closeDisruption(anomaly, "belt repaired").orElseThrow();

        assertEquals(ReplanningIncidentStatus.RESOLVED, closed.getStatus());
        assertTrue(registry.getCurrentIncident().isEmpty());
        verify(journal).recordRecovery(anomaly, "belt repaired", closed.getResolvedAt());
        verify(sse).broadcastResolved(closed);
    }

    @Test
    void theRecoveryIsShownInTheClosedIncidentAndJournaledWithTheCorrectionOnceItEnded() {
        notifications.notifyDisruption(anomaly);
        ReplanningIncidentResponse closed = notifications.closeDisruption(anomaly, "belt repaired").orElseThrow();
        AnomalyContext executing = anomaly.withRecoveryPlan(plan("cbRepaired", Status.EXECUTING));
        AnomalyContext executed = anomaly.withRecoveryPlan(plan("cbRepaired", Status.EXECUTED));

        ReplanningIncidentResponse updated = notifications.closeDisruption(executing, null).orElseThrow();
        assertEquals(executing.recoveryPlan(), updated.getRecoveryPlan());
        verify(journal, never()).recordRecovery(eq(executing), any(), any());

        notifications.closeDisruption(executed, null);
        verify(journal).recordRecovery(executed, "belt repaired", closed.getResolvedAt());
    }

    @Test
    void anIncidentAlreadyClosedIsNotClosedAgain() {
        notifications.notifyDisruption(anomaly);
        notifications.closeDisruption(anomaly, "belt repaired");

        Optional<ReplanningIncidentResponse> again = notifications.closeDisruption(anomaly, "again");

        assertTrue(again.isEmpty());
        verify(journal, never()).recordRecovery(any(), eq("again"), any());
    }
}
