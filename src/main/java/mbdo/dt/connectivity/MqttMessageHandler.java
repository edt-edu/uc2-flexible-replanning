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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class MqttMessageHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(MqttMessageHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GatewayService gatewayService;
    private final MqttOutboundGateway mqttOutbound;
    private final DigitalShadow digitalShadow;
    private final DtEngine dtEngine;
    private final MachineTelemetryBridge machineTelemetryBridge;

    public MqttMessageHandler(
            GatewayService gatewayService,
            @Qualifier("mqttOutboundGateway") MqttOutboundGateway mqttOutbound,
            DigitalShadow digitalShadow,
            DtEngine dtEngine,
            MachineTelemetryBridge machineTelemetryBridge
    ) {
        this.gatewayService = gatewayService;
        this.mqttOutbound = mqttOutbound;
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
            applyCloneRules(topic, payload);
            applyMqttToInfluxDBRules(topic, payload);
        } catch (RuntimeException e) {
            logger.warn("Failed to process MQTT message on topic '{}': {}", topic, e.getMessage());
        }
    }

    protected void applyCloneRules(String topic, String payload) {
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

            if (eventGroup.mqttSourcePrefix() == null || eventGroup.mqttTargetPrefix() == null) {
                continue;
            }

            if (eventGroup.dtEvents() == null) {
                continue;
            }

            for (DtEvent dtEvent : eventGroup.dtEvents()) {
                String ruleSourceTopic = eventGroup.mqttSourcePrefix() + dtEvent.mqttSourceTopic();

                if (MqttTopic.isMatched(ruleSourceTopic, topic)) {
                    String targetTopic = eventGroup.mqttTargetPrefix() + dtEvent.mqttTargetTopic();

                    mqttOutbound.sendToMqtt(targetTopic, payload);

                    DataRequest request = buildDataRequest(targetTopic, payload);
                    if (request != null) {
                        dtEngine.dispatch(request);
                        machineTelemetryBridge.onDataRequest(request);
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

    private DataRequest buildDataRequest(String targetTopic, String payload) {
        try {
            String[] parts = targetTopic.split("/");

            if (parts.length < 3) {
                logger.warn("Target topic has fewer than 3 segments, " +
                        "cannot extract model/attribute: {}", targetTopic);
                return null;
            }

            String modelName = parts[1];
            String attributeName = extractAttributeNameFromTargetTopic(parts);
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
                        logger.debug("Unparseable timestamp ignored for topic: {}",
                                targetTopic);
                    }
                }
            }

            return new DataRequest(targetTopic, modelName, attributeName, value, timestamp);

        } catch (Exception e) {
            logger.warn("Failed to build DataRequest for topic '{}': {}",
                    targetTopic, e.getMessage());
            return null;
        }
    }

    private String extractAttributeNameFromTargetTopic(String[] parts) {
        if (parts.length == 3) {
            return parts[2];
        }

        StringBuilder attributeName = new StringBuilder();

        for (int i = 2; i < parts.length; i++) {
            if (attributeName.length() > 0) {
                attributeName.append(".");
            }

            attributeName.append(parts[i]);
        }

        return attributeName.toString();
    }

    private String extractPayload(Object payload) {
        if (payload instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        return String.valueOf(payload);
    }
}