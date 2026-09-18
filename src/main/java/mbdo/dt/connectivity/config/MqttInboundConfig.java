package mbdo.dt.connectivity.config;

import mbdo.dt.connectivity.GatewayService;
import mbdo.dt.connectivity.MqttMessageHandler;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.ClientManager;
import org.springframework.integration.mqtt.core.Mqttv3ClientManager;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.util.UUID;

@Configuration
public class MqttInboundConfig {

    private static final Logger logger = LoggerFactory.getLogger(MqttInboundConfig.class);

    private final GatewayService gatewayService;

    public MqttInboundConfig(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public ClientManager<IMqttAsyncClient, MqttConnectOptions> mqttSourceClientManager() {
        MqttConnectOptions connectionOptions = new MqttConnectOptions();

        connectionOptions.setServerURIs(new String[]{
                gatewayService.getGatewayServiceConfiguration().mqttSourceBrokerUrl()
        });

        connectionOptions.setConnectionTimeout(30000);
        connectionOptions.setMaxReconnectDelay(1000);
        connectionOptions.setAutomaticReconnect(true);
        connectionOptions.setCleanSession(true);

        Mqttv3ClientManager clientManager = new Mqttv3ClientManager(
                connectionOptions,
                "gatewayInboundClient-" + UUID.randomUUID()
        );

        clientManager.setPersistence(new MemoryPersistence());

        return clientManager;
    }

    @Bean
    public MessageProducer inbound(
            ClientManager<IMqttAsyncClient, MqttConnectOptions> mqttSourceClientManager
    ) {
        String[] topics = gatewayService.getMonitoredMqttTopics().toArray(new String[0]);

        logger.info("Subscribing to {} MQTT topic(s):", topics.length);
        for (String topic : topics) {
            logger.info("  - {}", topic);
        }

        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(
                        mqttSourceClientManager,
                        topics
                );

        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel());

        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler(MqttMessageHandler mqttMessageHandler) {
        return mqttMessageHandler;
    }
}