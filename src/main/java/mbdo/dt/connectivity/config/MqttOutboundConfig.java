package mbdo.dt.connectivity.config;

import mbdo.dt.connectivity.GatewayService;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.util.UUID;

@Configuration
public class MqttOutboundConfig {

    private final GatewayService gatewayService;

    public MqttOutboundConfig(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Bean
    public MqttPahoClientFactory mqttTargetClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();

        MqttConnectOptions options = new MqttConnectOptions();

        options.setServerURIs(new String[]{
                gatewayService.getGatewayServiceConfiguration().mqttTargetBrokerUrl()
        });

        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        factory.setConnectionOptions(options);
        factory.setPersistence(new MemoryPersistence());

        return factory;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutbound() {
        MqttPahoMessageHandler messageHandler =
                new MqttPahoMessageHandler(
                        "gatewayOutboundClient-" + UUID.randomUUID(),
                        mqttTargetClientFactory()
                );

        messageHandler.setAsync(true);
        messageHandler.setDefaultTopic("gateway");

        return messageHandler;
    }

    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

}