package mbdo.dt.connectivity;

import java.io.Serializable;
import java.util.List;

public record GatewayServiceConfiguration(
        String name,
        String mqttSourceBrokerUrl,
        String mqttTargetBrokerUrl,
        List<MqttInfluxDbRecording> mqttInfluxDbRecordings,
        List<DtEventGroup> dtEventGroups
) implements Serializable {

    public record MqttInfluxDbRecording(
            String name,
            String mqttSourceTopic,
            String dataProcess,
            String timeProcess
    ) implements Serializable {
    }

    public record DtEventGroup(
            String name,
            String dataTransformation,
            String mqttSourcePrefix,
            String mqttTargetPrefix,
            List<DtEvent> dtEvents
    ) implements Serializable {
    }

    public record DtEvent(
            String mqttSourceTopic,
            String mqttTargetTopic
    ) implements Serializable {
    }
}