package mbdo.dt.domain;

import java.time.Instant;

public record AnomalyContext(
        String anomalyId,
        Instant detectionTimestamp,
        String nominalPath,
        String affectedStep,
        String affectedComponent,
        String currentWorkpieceLocation,
        String currentState,
        String message
) {
}