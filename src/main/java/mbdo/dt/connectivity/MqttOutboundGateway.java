package mbdo.dt.connectivity;

import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.handler.annotation.Header;

@MessagingGateway(defaultRequestChannel = "mqttOutboundChannel")
public interface MqttOutboundGateway {

    void sendToMqtt(@Header(MqttHeaders.TOPIC) String topic, String data);
}