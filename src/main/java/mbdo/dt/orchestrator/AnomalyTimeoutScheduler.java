package mbdo.dt.orchestrator;

import mbdo.dt.services.AnomalyDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically calls {@link AnomalyDetection#checkTimeTransitions()} so a state that overruns
 * its StateDurationLimit is noticed even when no new MQTT message arrives after the deadline
 * passes. evaluateObservation()/evaluateCurrentState() otherwise only run reactively, inside
 * message handling (see MqttMessageHandler / DtEngine).
 */
@Component
public class AnomalyTimeoutScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AnomalyTimeoutScheduler.class);

    private final AnomalyDetection anomalyDetection;

    public AnomalyTimeoutScheduler(AnomalyDetection anomalyDetection) {
        this.anomalyDetection = anomalyDetection;
    }

    @Scheduled(fixedDelayString = "${anomaly-detection.timeout-poll-interval-ms:2000}")
    public void pollForElapsedTimeouts() {
        try {
            anomalyDetection.checkTimeTransitions();
        } catch (RuntimeException e) {
            logger.warn("Scheduled StateDurationLimit check failed: {}", e.getMessage());
        }
    }
}
