package mbdo.dt.services.impl;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentJournal;
import mbdo.dt.incident.ReplanningIncidentRegistryService;
import mbdo.dt.incident.ReplanningIncidentResponse;
import mbdo.dt.incident.ReplanningIncidentSseService;
import mbdo.dt.replanning.ReplanningPlan;
import mbdo.dt.replanning.ReplanningPlan.Status;
import mbdo.dt.services.NotificationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final ReplanningIncidentRegistryService registryService;
    private final ReplanningIncidentSseService sseService;
    private final ReplanningIncidentJournal journal;

    public NotificationServiceImpl(
            ReplanningIncidentRegistryService registryService,
            ReplanningIncidentSseService sseService,
            ReplanningIncidentJournal journal
    ) {
        this.registryService = registryService;
        this.sseService = sseService;
        this.journal = journal;
    }

    @Override
    public boolean notifyDisruption(AnomalyContext context) {
        if (context == null) {
            return false;
        }
        Optional<ReplanningIncidentResponse> incident = registryService.findByAnomaly(context.anomalyId());
        ReplanningPlan plan = context.replanningPlan();
        if (plan == null) {
            if (incident.isPresent()) {
                // already notified
                return incident.get().getResolvedAt() == null;
            }
            journal.recordAnomaly(context);
            sseService.broadcastOpened(registryService.openIncident(context));
            return true;
        }
        // a plan only updates an incident: one that is no longer known (e.g. replaced by another disruption's) isn't
        // opened again
        Optional<ReplanningIncidentResponse> updated = registryService.attachPlan(context.anomalyId(), plan);
        updated.ifPresent(sseService::broadcastUpdated);
        // dropped: the incident was closed meanwhile, this plan is the replanning's last one
        if (ended(plan) || updated.isEmpty()) {
            journal.recordAnomaly(context);
        }
        return updated.isPresent();
    }

    @Override
    public Optional<ReplanningIncidentResponse> closeDisruption(AnomalyContext context, String comment) {
        if (context == null) {
            return Optional.empty();
        }
        Optional<ReplanningIncidentResponse> incident = registryService.findByAnomaly(context.anomalyId());
        ReplanningPlan recovery = context.recoveryPlan();
        if (recovery != null) {
            Optional<ReplanningIncidentResponse> updated = registryService.attachRecoveryPlan(context.anomalyId(),
                    recovery);
            updated.ifPresent(closed -> {
                sseService.broadcastUpdated(closed);
                if (ended(recovery)) {
                    journal.recordRecovery(context, closed.getOperatorComment(), closed.getResolvedAt());
                }
            });
            return updated;
        }
        // the same time for the record and the incident: the recovery is added to the record at that time
        Instant resolvedAt = Instant.now();
        Optional<ReplanningIncidentResponse> resolved = incident.flatMap(open ->
                registryService.resolveCurrentIncident(open.getId(), comment, resolvedAt));
        resolved.ifPresent(closed -> {
            journal.recordRecovery(context, comment, resolvedAt);
            sseService.broadcastResolved(closed);
        });
        return resolved;
    }

    private static boolean ended(ReplanningPlan plan) {
        return plan.status() == Status.EXECUTED || plan.status() == Status.FAILED;
    }
}
