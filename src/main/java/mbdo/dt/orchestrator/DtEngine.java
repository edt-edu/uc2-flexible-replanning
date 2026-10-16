package mbdo.dt.orchestrator;

import mbdo.dt.connectivity.GatewayService;
import mbdo.dt.connectivity.GatewayServiceConfiguration;
import mbdo.dt.connectivity.GatewayServiceConfiguration.DtEvent;
import mbdo.dt.connectivity.GatewayServiceConfiguration.DtEventGroup;
import mbdo.dt.connectivity.GatewayServiceConfiguration.MqttInfluxDbRecording;
import mbdo.dt.data.DigitalShadow;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.incident.ReplanningIncidentResponse;
import mbdo.dt.models.SysMLV2Parser;
import mbdo.dt.services.AnomalyDetectionService;
import mbdo.dt.services.AnomalyDetectionService.AnomalyEvaluationResult;
import mbdo.dt.services.NotificationService;
import mbdo.dt.services.ReplanningService;
import mbdo.dt.services.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DtEngine {

    private static final Logger logger = LoggerFactory.getLogger(DtEngine.class);

    private final GatewayService gatewayService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final ReplanningService replanningService;
    private final NotificationService notificationService;
    private final DigitalShadow digitalShadow;
    private final SysMLV2Parser sysMLV2Parser;
    private final RuntimeContext runtimeContextService;
    private final ResourcePatternResolver resourcePatternResolver;

    @Value("${sysml.models.path:}")
    private String sysmlModelsPath;

    private volatile boolean active;
    private volatile boolean databaseAvailable;

    public DtEngine(
            GatewayService gatewayService,
            DigitalShadow digitalShadow,
            AnomalyDetectionService anomalyDetectionService,
            ReplanningService replanningService,
            NotificationService notificationService,
            SysMLV2Parser sysMLV2Parser,
            ResourceLoader resourceLoader,
            RuntimeContext runtimeContextService
    ) {
        this.gatewayService = gatewayService;
        this.digitalShadow = digitalShadow;
        this.anomalyDetectionService = anomalyDetectionService;
        this.replanningService = replanningService;
        this.notificationService = notificationService;
        this.sysMLV2Parser = sysMLV2Parser;
        this.runtimeContextService = runtimeContextService;
        this.resourcePatternResolver = new PathMatchingResourcePatternResolver(resourceLoader);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        logger.info("DtEngine is starting...");

        initializeDatabase();
        initializeGateway();
        printMonitoredTopics();
        printMappingRules();
        printInfluxRecordingRules();
        initializeRuntimeServices();
        activateRuntime();

        logger.info("Digital Twin is UP & Running...");
    }

    public void dispatch(DataRequest request) {
        if (request == null) {
            logger.warn("received null DataRequest. Ignoring.");
            return;
        }

        if (!active) {
            logger.warn("received DataRequest before engine is active. Dropping: {}", request.topic());
            return;
        }

        logger.debug("dispatching: {}.{} = {}",
                request.modelName(),
                request.attributeName(),
                request.value()
        );

        try {
            Map<String, Object> snapshot = synchronizeRuntimeState(request);

            AnomalyEvaluationResult result = anomalyDetectionService.evaluateObservation(request, snapshot);

            runtimeContextService.updateResolvedRuntimeState(
                    result.activeStateMachineId(),
                    result.activeStateMachineName(),
                    result.currentState()
            );
            reactToDisruptions(snapshot);
        } catch (RuntimeException e) {
            logger.error("failed to process DataRequest for topic '{}': {}",
                    request.topic(), e.getMessage(), e);
        }
    }

    /** Applies the observation to the runtime context, and returns the latest observed values. */
    private Map<String, Object> synchronizeRuntimeState(DataRequest request) {
        runtimeContextService.onDataRequest(request);
        return runtimeContextService.snapshot();
    }

    /**
     * Evaluates the time-based transitions without a new observation (a state overrunning its time limit), and reacts
     * to the anomalies detected (see {@link AnomalyTimeoutScheduler}).
     */
    public void checkTimeTransitions() {
        anomalyDetectionService.checkTimeTransitions();
        reactToDisruptions(runtimeContextService.snapshot());
    }

    /** Opens the incident of each anomaly detected, then replans: the replanning shows its plan in that incident. */
    private void reactToDisruptions(Map<String, Object> observedValues) {
        for (AnomalyEvaluationResult disruption : anomalyDetectionService.detectDisruptions()) {
            AnomalyContext context = disruption.anomalyContext();
            try {
                notificationService.notifyDisruption(context);
            } catch (RuntimeException e) {
                logger.warn("Failed to notify anomaly '{}': {}", context.anomalyId(), e.getMessage());
            }
            replanningService.handleDisruption(disruption, observedValues);
        }
    }

    /**
     * The operator corrected the disruption: closes its incident, then its token is no longer followed (the others
     * are), and the machines go back to their nominal missions.
     *
     * @return the closed incident, or empty if the incident of {@code context} is not open
     */
    public Optional<ReplanningIncidentResponse> resolveDisruption(AnomalyContext context, String comment) {
        return notificationService.closeDisruption(context, comment).map(resolved -> {
            anomalyDetectionService.releaseAnomaly(context.anomalyId());
            replanningService.recover(context.anomalyId());
            return resolved;
        });
    }

    private void initializeRuntimeServices() {
        logger.info("Initializing runtime services.");

        String configuredPath = sysmlModelsPath;

        if (configuredPath == null || configuredPath.isBlank()) {
            logger.warn("No SysML model path configured. " +
                    "Anomaly detection will return MODEL_NOT_LOADED.");
            return;
        }

        try {
            List<SysMLV2Parser.ParsedSysMLModel> parsedModels =
                    loadAllSysMLModels(configuredPath);

            if (parsedModels.isEmpty()) {
                logger.warn("No SysML model files found for path: {}",
                        configuredPath);
                return;
            }

            // before the anomaly detection: an anomaly is replanned as soon as it is detected
            replanningService.activateModels(parsedModels);
            anomalyDetectionService.activateModels(parsedModels);

            logger.info("Runtime services initialized with {} SysML model(s).",
                    parsedModels.size());

        } catch (RuntimeException | IOException e) {
            logger.error("Failed to initialize runtime services: {}",
                    e.getMessage(), e);
        }
    }

    private List<SysMLV2Parser.ParsedSysMLModel> loadAllSysMLModels(
            String configuredPath
    ) throws IOException {
        String pattern = toSysMLResourcePattern(configuredPath);

        Resource[] resources = resourcePatternResolver.getResources(pattern);

        List<SysMLV2Parser.ParsedSysMLModel> parsedModels = new ArrayList<>();

        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }

            logger.info("Loading SysML model: {}",
                    resource.getDescription());

            parsedModels.add(sysMLV2Parser.loadModel(resource));
        }

        return parsedModels;
    }

    private String toSysMLResourcePattern(String configuredPath) {
        if (configuredPath.contains("*") || configuredPath.endsWith(".sysml")) {
            return configuredPath;
        }

        String normalizedPath = configuredPath;

        if (!normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath + "/";
        }

        return normalizedPath + "**/*.sysml";
    }

    private void initializeDatabase() {
        databaseAvailable = digitalShadow.isAvailable();

        if (databaseAvailable) {
            logger.info("InfluxDB connection available.");
        } else {
            logger.warn("InfluxDB connection not available.");
        }
    }

    private void initializeGateway() {
        GatewayServiceConfiguration configuration =
                gatewayService.getGatewayServiceConfiguration();

        logger.info("gateway configuration loaded.");
        logger.info("configuration name: {}", configuration.name());
        logger.info("source mqtt broker: {}", configuration.mqttSourceBrokerUrl());
        logger.info("target mqtt broker: {}", configuration.mqttTargetBrokerUrl());
    }

    private void printMonitoredTopics() {
        List<String> monitoredTopics = gatewayService.getMonitoredMqttTopics();

        logger.info("mqtt topics monitored by the DT:");

        if (monitoredTopics.isEmpty()) {
            logger.info("no monitored mqtt topics configured.");
            return;
        }

        for (String topic : monitoredTopics) {
            logger.info("  - {}", topic);
        }
    }

    private void printMappingRules() {
        GatewayServiceConfiguration configuration =
                gatewayService.getGatewayServiceConfiguration();

        List<DtEventGroup> eventGroups = safeList(configuration.dtEventGroups());

        if (eventGroups.isEmpty()) {
            logger.info("no DT event mapping rules configured.");
            return;
        }

        logger.info("mqtt mapping rules:");

        for (DtEventGroup eventGroup : eventGroups) {
            logger.info("Group: {}", eventGroup.name());
            logger.info("transformation: {}", eventGroup.dataTransformation());
            logger.info("source prefix : {}", eventGroup.mqttSourcePrefix());
            logger.info("target prefix : {}", eventGroup.mqttTargetPrefix());

            List<DtEvent> events = safeList(eventGroup.dtEvents());

            if (events.isEmpty()) {
                logger.info("No events configured.");
                continue;
            }

            for (DtEvent event : events) {
                String sourceTopic = eventGroup.mqttSourcePrefix() + event.mqttSourceTopic();
                String targetTopic = eventGroup.mqttTargetPrefix() + event.mqttTargetTopic();

                logger.info("    {} -> {}", sourceTopic, targetTopic);
            }
        }
    }

    private void printInfluxRecordingRules() {
        GatewayServiceConfiguration configuration =
                gatewayService.getGatewayServiceConfiguration();

        List<MqttInfluxDbRecording> recordings =
                safeList(configuration.mqttInfluxDbRecordings());

        if (recordings.isEmpty()) {
            logger.info("no mqtt to InfluxDB recording rules configured.");
            return;
        }

        logger.info("mqtt to InfluxDB recording rules:");

        for (MqttInfluxDbRecording recording : recordings) {
            logger.info("measurement: {}", recording.name());
            logger.info("source topic: {}", recording.mqttSourceTopic());
            logger.info("data process: {}", recording.dataProcess());
            logger.info("time process: {}", recording.timeProcess());
        }
    }

    private void activateRuntime() {
        active = true;

        logger.info("runtime services activated.");
        logger.info("InfluxDB storage: {}",
                databaseAvailable ? "enabled" : "unavailable");
    }

    private <T> List<T> safeList(List<T> values) {
        if (values == null) {
            return Collections.emptyList();
        }

        return values;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDatabaseAvailable() {
        return databaseAvailable;
    }
}