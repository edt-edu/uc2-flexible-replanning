package mbdo.dt.connectivity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mbdo.dt.connectivity.GatewayServiceConfiguration.DtEvent;
import mbdo.dt.connectivity.GatewayServiceConfiguration.DtEventGroup;
import mbdo.dt.connectivity.GatewayServiceConfiguration.MqttInfluxDbRecording;
import mbdo.dt.data.DigitalShadow;
import mbdo.dt.domain.DataRequest;
import mbdo.dt.orchestrator.DtEngine;
import mbdo.dt.telemetry.MachineTelemetryBridge;
import mbdo.dt.utils.TimestampParsingUtils;
import mbdo.dt.utils.TypeDetector;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Component
public class MqttMessageHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(MqttMessageHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GatewayService gatewayService;
    private final DigitalShadow digitalShadow;
    private final DtEngine dtEngine;
    private final MachineTelemetryBridge machineTelemetryBridge;

    public MqttMessageHandler(
            GatewayService gatewayService,
            DigitalShadow digitalShadow,
            DtEngine dtEngine,
            MachineTelemetryBridge machineTelemetryBridge
    ) {
        this.gatewayService = gatewayService;
        this.digitalShadow = digitalShadow;
        this.dtEngine = dtEngine;
        this.machineTelemetryBridge = machineTelemetryBridge;
    }

    @Override
    public void handleMessage(Message<?> message) throws MessagingException {
        String topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC, String.class);

        Boolean retained = message.getHeaders().get(MqttHeaders.RECEIVED_RETAINED, Boolean.class);
        if (Boolean.TRUE.equals(retained)) {
            logger.debug("Ignoring retained MQTT message on topic '{}' (not a live publish)", topic);
            return;
        }

        String payload = extractPayload(message.getPayload());

        try {
            applyMachineTelemetry(topic, payload);
            applyDtMappingRules(topic, payload);
            applyMqttToInfluxDBRules(topic, payload);
        } catch (RuntimeException e) {
            logger.warn("Failed to process MQTT message on topic '{}': {}", topic, e.getMessage());
        }
    }

    /** Pushes every platform gateway measurement to the shopfloor UI, whether or not the DT model uses it. */
    protected void applyMachineTelemetry(String topic, String payload) {
        String filter = gatewayService.getMachineTelemetryTopicFilter();
        if (topic == null || filter == null || !MqttTopic.isMatched(filter, topic)) {
            return;
        }

        Measurement measurement = parseMeasurement(topic, payload);
        if (measurement != null) {
            machineTelemetryBridge.onGatewayMeasurement(topic, measurement.value(), measurement.timestamp());
        }
    }

    /** Maps gateway measurements onto SysML model attributes and dispatches them to the DT engine. */
    protected void applyDtMappingRules(String topic, String payload) {
        if (topic == null) {
            return;
        }

        List<DtEventGroup> eventGroups =
                gatewayService.getGatewayServiceConfiguration().dtEventGroups();

        if (eventGroups == null) {
            return;
        }

        for (DtEventGroup eventGroup : eventGroups) {
            if (!"clone".equals(eventGroup.dataTransformation())) {
                continue;
            }

            if (eventGroup.mqttSourcePrefix() == null || eventGroup.dtEvents() == null) {
                continue;
            }

            for (DtEvent dtEvent : eventGroup.dtEvents()) {
                String ruleSourceTopic = eventGroup.mqttSourcePrefix() + dtEvent.mqttSourceTopic();

                if (MqttTopic.isMatched(ruleSourceTopic, topic)) {
                    DataRequest request = buildDataRequest(topic, dtEvent.mqttTargetTopic(), payload);
                    if (request != null) {
                        dtEngine.dispatch(request);
                    }
                }
            }
        }
    }

    protected void applyMqttToInfluxDBRules(String topic, String payload) {
        if (topic == null) {
            return;
        }

        List<MqttInfluxDbRecording> recordings =
                gatewayService.getGatewayServiceConfiguration().mqttInfluxDbRecordings();

        if (recordings == null) {
            return;
        }

        for (MqttInfluxDbRecording recording : recordings) {
            if (MqttTopic.isMatched(recording.mqttSourceTopic(), topic)) {
                digitalShadow.processAndStoreMessage(
                        recording.name(),
                        topic,
                        payload,
                        "extract-json-timestamp".equals(recording.timeProcess()),
                        "extract-json-value".equals(recording.dataProcess())
                );
            }
        }
    }

    private record Measurement(Object value, Long timestamp) {
    }

    /**
     * @param modelTarget "&lt;SysML model&gt;/&lt;attribute path&gt;", e.g. "TokenLocation/vgr2/vacuumActValve"
     *                    becomes model "TokenLocation" and attribute "vgr2.vacuumActValve".
     */
    private DataRequest buildDataRequest(String sourceTopic, String modelTarget, String payload) {
        String[] parts = modelTarget == null ? new String[0] : modelTarget.split("/");

        if (parts.length < 2) {
            logger.warn("Mapping target has fewer than 2 segments, " +
                    "cannot extract model/attribute: {}", modelTarget);
            return null;
        }

        Measurement measurement = parseMeasurement(sourceTopic, payload);
        if (measurement == null) {
            return null;
        }

        String modelName = parts[0];
        String attributeName = String.join(".", Arrays.copyOfRange(parts, 1, parts.length));

        return new DataRequest(sourceTopic, modelName, attributeName, measurement.value(), measurement.timestamp());
    }

    private Measurement parseMeasurement(String topic, String payload) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(payload);

            Object value = null;
            if (json.has("value")) {
                value = TypeDetector.detectType(json.get("value").asText());
            }

            Long timestamp = null;
            if (json.has("timestamp")) {
                JsonNode tsNode = json.get("timestamp");
                if (tsNode.isNumber()) {
                    timestamp = tsNode.asLong();
                } else {
                    timestamp = TimestampParsingUtils.parseEpochMillisOrNull(tsNode.asText());
                    if (timestamp == null) {
                        logger.debug("Unparseable timestamp ignored for topic: {}", topic);
                    }
                }
            }

            return new Measurement(value, timestamp);

        } catch (Exception e) {
            logger.warn("Failed to parse payload on topic '{}': {}", topic, e.getMessage());
            return null;
        }
    }

    private String extractPayload(Object payload) {
        if (payload instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        return String.valueOf(payload);
    }
}