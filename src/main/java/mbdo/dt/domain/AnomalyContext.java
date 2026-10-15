package mbdo.dt.domain;

import mbdo.dt.replanning.ReplanningPlan;

import java.time.Instant;

/**
 * @param replanningPlan the replanning the anomaly triggered, as it ended (null if none, or not ended yet)
 * @param recoveryPlan   the recovery executed once the operator repaired the failure, as it ended (null if none)
 */
public record AnomalyContext(
        String anomalyId,
        Instant detectionTimestamp,
        String nominalPath,
        String affectedStep,
        String affectedComponent,
        String currentWorkpieceLocation,
        String currentState,
        String message,
        ReplanningPlan replanningPlan,
        ReplanningPlan recoveryPlan
) {

    public AnomalyContext(
            String anomalyId,
            Instant detectionTimestamp,
            String nominalPath,
            String affectedStep,
            String affectedComponent,
            String currentWorkpieceLocation,
            String currentState,
            String message
    ) {
        this(anomalyId, detectionTimestamp, nominalPath, affectedStep, affectedComponent, currentWorkpieceLocation,
                currentState, message, null, null);
    }

    public AnomalyContext withReplanningPlan(ReplanningPlan plan) {
        return new AnomalyContext(anomalyId, detectionTimestamp, nominalPath, affectedStep, affectedComponent,
                currentWorkpieceLocation, currentState, message, plan, recoveryPlan);
    }

    public AnomalyContext withRecoveryPlan(ReplanningPlan plan) {
        return new AnomalyContext(anomalyId, detectionTimestamp, nominalPath, affectedStep, affectedComponent,
                currentWorkpieceLocation, currentState, message, replanningPlan, plan);
    }
}
