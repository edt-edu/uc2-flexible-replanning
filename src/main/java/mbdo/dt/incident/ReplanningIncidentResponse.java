package mbdo.dt.incident;

import mbdo.dt.replanning.ReplanningPlan;

import java.time.Instant;

/**
 * A replanning incident as pushed to / consumed by the factory-supervision UI.
 * Mirrors the frontend's ReplanningIncident model, without cep-service's Rule/Incident
 * JPA baggage (there is no rule engine in this app - incidents are raised from
 * {@link mbdo.dt.domain.AnomalyContext} instead, see {@link mbdo.dt.services.NotificationService}).
 */
public class ReplanningIncidentResponse {

    private final long id;
    private final String machineId;
    private final String message;
    private final String requiredAction;
    private final ReplanningIncidentStatus status;
    private final Instant startTime;
    private final Instant createdAt;
    private final Instant resolvedAt;
    private final String operatorComment;
    private final ReplanningPlan replanningPlan;
    private final ReplanningPlan recoveryPlan;

    public ReplanningIncidentResponse(
            long id,
            String machineId,
            String message,
            String requiredAction,
            ReplanningIncidentStatus status,
            Instant startTime,
            Instant createdAt,
            Instant resolvedAt,
            String operatorComment
    ) {
        this(id, machineId, message, requiredAction, status, startTime, createdAt, resolvedAt, operatorComment, null,
                null);
    }

    public ReplanningIncidentResponse(
            long id,
            String machineId,
            String message,
            String requiredAction,
            ReplanningIncidentStatus status,
            Instant startTime,
            Instant createdAt,
            Instant resolvedAt,
            String operatorComment,
            ReplanningPlan replanningPlan,
            ReplanningPlan recoveryPlan
    ) {
        this.id = id;
        this.machineId = machineId;
        this.message = message;
        this.requiredAction = requiredAction;
        this.status = status;
        this.startTime = startTime;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
        this.operatorComment = operatorComment;
        this.replanningPlan = replanningPlan;
        this.recoveryPlan = recoveryPlan;
    }

    public long getId() {
        return id;
    }

    public String getMachineId() {
        return machineId;
    }

    public String getMessage() {
        return message;
    }

    public String getRequiredAction() {
        return requiredAction;
    }

    public ReplanningIncidentStatus getStatus() {
        return status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getOperatorComment() {
        return operatorComment;
    }

    /** The replanning of the incident (waiting, being executed, or executed), null if none. */
    public ReplanningPlan getReplanningPlan() {
        return replanningPlan;
    }

    /** The recovery executed once the operator repaired the failure (back to the nominal missions), null if none. */
    public ReplanningPlan getRecoveryPlan() {
        return recoveryPlan;
    }

    public ReplanningIncidentResponse resolved(String comment, Instant resolvedAt) {
        return new ReplanningIncidentResponse(
                id, machineId, message, requiredAction, ReplanningIncidentStatus.RESOLVED,
                startTime, createdAt, resolvedAt, comment, replanningPlan, recoveryPlan
        );
    }

    public ReplanningIncidentResponse withPlan(ReplanningPlan plan) {
        return new ReplanningIncidentResponse(
                id, machineId, message, requiredAction, status,
                startTime, createdAt, resolvedAt, operatorComment, plan, recoveryPlan
        );
    }

    public ReplanningIncidentResponse withRecoveryPlan(ReplanningPlan plan) {
        return new ReplanningIncidentResponse(
                id, machineId, message, requiredAction, status,
                startTime, createdAt, resolvedAt, operatorComment, replanningPlan, plan
        );
    }
}
