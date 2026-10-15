package mbdo.dt.incident;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.replanning.ReplanningPlan;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side authority for the "current open incident" + resolved history shown by
 * the factory-supervision UI, so every connected client/role sees the same state
 * (cep-service's replanning-incident.store.ts used to mock this entirely client-side).
 */
@Service
public class ReplanningIncidentRegistryService {

    private static final int MAX_HISTORY = 50;

    private final AtomicLong idSequence = new AtomicLong(1);
    private final Map<Long, AnomalyContext> originatingContexts = new ConcurrentHashMap<>();
    private final Deque<ReplanningIncidentResponse> history = new ArrayDeque<>();

    private volatile ReplanningIncidentResponse currentIncident;

    public synchronized ReplanningIncidentResponse openIncident(AnomalyContext context) {
        ReplanningIncidentResponse incident = newIncident(context, ReplanningIncidentStatus.OPEN, Instant.now(), null,
                null, null, null);
        currentIncident = incident;
        return incident;
    }

    /**
     * Restores the incidents of the journal (InfluxDB) at startup, oldest first, before the ones of this run: they are
     * resolved (or were never, the digital twin stopped meanwhile).
     */
    public synchronized void restoreHistory(List<RestoredIncident> journal) {
        List<ReplanningIncidentResponse> restored = journal.stream()
                .map(r -> newIncident(r.anomaly(), ReplanningIncidentStatus.RESOLVED, r.anomaly().detectionTimestamp(),
                        r.resolvedAt(), r.comment(), r.anomaly().replanningPlan(), r.anomaly().recoveryPlan()))
                .toList();
        // behind the incidents of this run, newest first
        for (int i = restored.size() - 1; i >= 0; i--) {
            history.addLast(restored.get(i));
        }
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }

    /** An incident of the journal: its anomaly (with its replannings) and its correction. */
    public record RestoredIncident(AnomalyContext anomaly, Instant resolvedAt, String comment) {
    }

    private ReplanningIncidentResponse newIncident(AnomalyContext context, ReplanningIncidentStatus status,
                                                   Instant createdAt, Instant resolvedAt, String comment,
                                                   ReplanningPlan plan, ReplanningPlan recoveryPlan) {
        long id = idSequence.getAndIncrement();
        Instant startTime = context.detectionTimestamp() != null ? context.detectionTimestamp() : Instant.now();
        originatingContexts.put(id, context);
        return new ReplanningIncidentResponse(
                id,
                context.affectedComponent(),
                context.message(),
                "Investigate and acknowledge the anomaly on " + context.affectedComponent(),
                status,
                startTime,
                createdAt,
                resolvedAt,
                comment,
                plan,
                recoveryPlan
        );
    }

    public synchronized Optional<ReplanningIncidentResponse> resolveCurrentIncident(long id, String comment) {
        return resolveCurrentIncident(id, comment, Instant.now());
    }

    public synchronized Optional<ReplanningIncidentResponse> resolveCurrentIncident(long id, String comment,
                                                                                    Instant resolvedAt) {
        if (currentIncident == null || currentIncident.getId() != id) {
            return Optional.empty();
        }

        ReplanningIncidentResponse resolved = currentIncident.resolved(comment, resolvedAt);
        currentIncident = null;

        history.addFirst(resolved);
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }

        return Optional.of(resolved);
    }

    /**
     * Attaches a replanning plan to the open incident raised by the anomaly {@code anomalyId}.
     *
     * @return the updated incident, or empty if that incident is not open any more
     */
    public synchronized Optional<ReplanningIncidentResponse> attachPlan(String anomalyId, ReplanningPlan plan) {
        if (currentIncident == null) {
            return Optional.empty();
        }
        AnomalyContext context = originatingContexts.get(currentIncident.getId());
        if (context == null || !context.anomalyId().equals(anomalyId)) {
            return Optional.empty();
        }
        currentIncident = currentIncident.withPlan(plan);
        return Optional.of(currentIncident);
    }

    /**
     * Attaches the recovery plan to the resolved incident raised by the anomaly {@code anomalyId}.
     *
     * @return the updated incident, or empty if it isn't in the history
     */
    public synchronized Optional<ReplanningIncidentResponse> attachRecoveryPlan(String anomalyId,
                                                                                ReplanningPlan plan) {
        List<ReplanningIncidentResponse> incidents = new ArrayList<>(history);
        for (int i = 0; i < incidents.size(); i++) {
            AnomalyContext context = originatingContexts.get(incidents.get(i).getId());
            if (context != null && context.anomalyId().equals(anomalyId)) {
                ReplanningIncidentResponse updated = incidents.get(i).withRecoveryPlan(plan);
                incidents.set(i, updated);
                history.clear();
                history.addAll(incidents);
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    /** The incident raised by the anomaly {@code anomalyId} (open or resolved), and its anomaly. */
    public synchronized Optional<ReplanningIncidentResponse> findByAnomaly(String anomalyId) {
        if (currentIncident != null && isRaisedBy(currentIncident, anomalyId)) {
            return Optional.of(currentIncident);
        }
        return history.stream().filter(incident -> isRaisedBy(incident, anomalyId)).findFirst();
    }

    private boolean isRaisedBy(ReplanningIncidentResponse incident, String anomalyId) {
        AnomalyContext context = originatingContexts.get(incident.getId());
        return context != null && context.anomalyId().equals(anomalyId);
    }

    public Optional<AnomalyContext> getOriginatingContext(long id) {
        return Optional.ofNullable(originatingContexts.get(id));
    }

    public Optional<ReplanningIncidentResponse> getCurrentIncident() {
        return Optional.ofNullable(currentIncident);
    }

    public synchronized List<ReplanningIncidentResponse> getHistory() {
        return List.copyOf(history);
    }
}
