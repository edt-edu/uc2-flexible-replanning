package mbdo.dt.incident;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.replanning.ReplanningPlan;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
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
        long id = idSequence.getAndIncrement();
        Instant startTime = context.detectionTimestamp() != null ? context.detectionTimestamp() : Instant.now();

        ReplanningIncidentResponse incident = new ReplanningIncidentResponse(
                id,
                context.affectedComponent(),
                context.message(),
                "Investigate and acknowledge the anomaly on " + context.affectedComponent(),
                ReplanningIncidentStatus.OPEN,
                startTime,
                Instant.now(),
                null,
                null
        );

        originatingContexts.put(id, context);
        currentIncident = incident;
        return incident;
    }

    public synchronized Optional<ReplanningIncidentResponse> resolveCurrentIncident(long id, String comment) {
        if (currentIncident == null || currentIncident.getId() != id) {
            return Optional.empty();
        }

        ReplanningIncidentResponse resolved = currentIncident.resolved(comment, Instant.now());
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
