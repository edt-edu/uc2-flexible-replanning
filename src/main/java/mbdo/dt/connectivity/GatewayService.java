package mbdo.dt.connectivity;

import mbdo.dt.connectivity.GatewayServiceConfiguration.DtEvent;
import mbdo.dt.connectivity.GatewayServiceConfiguration.DtEventGroup;
import mbdo.dt.connectivity.GatewayServiceConfiguration.MqttInfluxDbRecording;
import mbdo.dt.utils.YamlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

@Service
public class GatewayService {

    private static final Logger logger = LoggerFactory.getLogger(GatewayService.class);

    private static final String SOURCE_BROKER_OVERRIDE_ENV = "MQTT_SOURCE_BROKER_URL";
    private static final String TARGET_BROKER_OVERRIDE_ENV = "MQTT_TARGET_BROKER_URL";

    private final ApplicationContext applicationContext;
    private final GatewayServiceConfiguration gatewayServiceConfiguration;
    private final GatewayConfigurationLocator gatewayConfigurationLocator;

    public GatewayService(
            GatewayConfigurationLocator gatewayConfigurationLocator,
            ApplicationContext applicationContext
    ) {
        this.gatewayConfigurationLocator = gatewayConfigurationLocator;
        this.applicationContext = applicationContext;
        this.gatewayServiceConfiguration = loadGatewayServiceConfiguration();
    }

    protected GatewayServiceConfiguration loadGatewayServiceConfiguration() {
        GatewayServiceConfiguration parsed = YamlUtils.convertYamlToObject(
                applicationContext,
                gatewayConfigurationLocator.getConfigurationFilePath(),
                GatewayServiceConfiguration.class
        );

        return applyBrokerUrlOverrides(parsed);
    }

    private GatewayServiceConfiguration applyBrokerUrlOverrides(GatewayServiceConfiguration parsed) {
        String sourceOverride = System.getenv(SOURCE_BROKER_OVERRIDE_ENV);
        String targetOverride = System.getenv(TARGET_BROKER_OVERRIDE_ENV);

        if (sourceOverride == null && targetOverride == null) {
            return parsed;
        }

        String sourceBrokerUrl = sourceOverride != null ? sourceOverride : parsed.mqttSourceBrokerUrl();
        String targetBrokerUrl = targetOverride != null ? targetOverride : parsed.mqttTargetBrokerUrl();

        if (sourceOverride != null) {
            logger.info("{} set - overriding mqttSourceBrokerUrl: {}", SOURCE_BROKER_OVERRIDE_ENV, sourceBrokerUrl);
        }
        if (targetOverride != null) {
            logger.info("{} set - overriding mqttTargetBrokerUrl: {}", TARGET_BROKER_OVERRIDE_ENV, targetBrokerUrl);
        }

        return new GatewayServiceConfiguration(
                parsed.name(),
                sourceBrokerUrl,
                targetBrokerUrl,
                parsed.mqttInfluxDbRecordings(),
                parsed.dtEventGroups()
        );
    }

    public GatewayServiceConfiguration getGatewayServiceConfiguration() {
        return gatewayServiceConfiguration;
    }

    public List<String> getMonitoredMqttTopics() {
        HashSet<String> monitoredTopics = new HashSet<>();

        if (gatewayServiceConfiguration.mqttInfluxDbRecordings() != null) {
            for (MqttInfluxDbRecording influxRecording : gatewayServiceConfiguration.mqttInfluxDbRecordings()) {
                monitoredTopics.add(influxRecording.mqttSourceTopic());
            }
        }

        if (gatewayServiceConfiguration.dtEventGroups() != null) {
            for (DtEventGroup dtEventGroup : gatewayServiceConfiguration.dtEventGroups()) {
                for (DtEvent dtEvent : dtEventGroup.dtEvents()) {
                    monitoredTopics.add(dtEventGroup.mqttSourcePrefix() + dtEvent.mqttSourceTopic());
                }
            }
        }

        List<String> result = new ArrayList<>(monitoredTopics);
        Collections.sort(result);
        return result;
    }

}