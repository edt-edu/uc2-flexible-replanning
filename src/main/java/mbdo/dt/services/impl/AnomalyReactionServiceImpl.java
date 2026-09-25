package mbdo.dt.services.impl;

import mbdo.dt.data.DigitalShadow;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;
import mbdo.dt.services.AnomalyReactionService;
import mbdo.dt.services.NotificationService;
import mbdo.dt.services.ReplanningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AnomalyReactionServiceImpl implements AnomalyReactionService {

    private static final Logger logger = LoggerFactory.getLogger(AnomalyReactionServiceImpl.class);

    private final ReplanningService replanningService;
    private final DigitalShadow digitalShadow;
    private final NotificationService notificationService;

    @Autowired
    public AnomalyReactionServiceImpl(
            ReplanningService replanningService,
            DigitalShadow digitalShadow,
            NotificationService notificationService
    ) {
        this.replanningService = replanningService;
        this.digitalShadow = digitalShadow;
        this.notificationService = notificationService;
    }

    @Override
    public void handleDetectedAnomaly(
            DataRequest triggeringRequest,
            AnomalyEvaluationResult result,
            Map<String, Object> latestRuntimeValues
    ) {
        if (result == null || result.anomalyContext() == null) {
            return;
        }

        AnomalyContext anomalyContext = result.anomalyContext();

        if (triggeringRequest == null) {
            logger.info(
                    "Anomaly detected without a live triggering request (time-based check). "
                            + "Forwarding to replanning with a null triggeringRequest."
            );
        }

        try {
            digitalShadow.recordAnomaly(anomalyContext);
        } catch (RuntimeException e) {
            logger.warn("Failed to record anomaly '{}' in the digital shadow: {}",
                    anomalyContext.anomalyId(), e.getMessage());
        }

        try {
            notificationService.notifyAnomaly(anomalyContext);
        } catch (RuntimeException e) {
            logger.warn("Failed to notify anomaly '{}': {}",
                    anomalyContext.anomalyId(), e.getMessage());
        }

        replanningService.handleAnomaly(triggeringRequest, result, latestRuntimeValues);
    }
}
