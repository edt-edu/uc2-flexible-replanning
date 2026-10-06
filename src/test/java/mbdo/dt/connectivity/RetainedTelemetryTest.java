package mbdo.dt.connectivity;

import mbdo.dt.data.DigitalShadow;
import mbdo.dt.orchestrator.DtEngine;
import mbdo.dt.telemetry.MachineTelemetryBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A retained gateway message restores the shopfloor UI state, but is not a live observation for the DT. */
class RetainedTelemetryTest {

    private static final String TOPIC = "gateway/VIRTUAL_1C1H1M1S2V_01/VGRSystemModel_01/vacuumSensRotEncoderCounter";
    private static final String PAYLOAD = "{\"value\": 2145, \"timestamp\": \"2026-09-29T12:48:14.618231+00:00Z\"}";

    private GatewayService gatewayService;
    private DigitalShadow digitalShadow;
    private DtEngine dtEngine;
    private MachineTelemetryBridge bridge;
    private MqttMessageHandler handler;

    @BeforeEach
    void setUp() {
        gatewayService = mock(GatewayService.class);
        digitalShadow = mock(DigitalShadow.class);
        dtEngine = mock(DtEngine.class);
        bridge = mock(MachineTelemetryBridge.class);
        when(gatewayService.getMachineTelemetryTopicFilter()).thenReturn("gateway/#");
        when(gatewayService.getGatewayServiceConfiguration()).thenReturn(new GatewayServiceConfiguration(
                "test", "tcp://localhost:1883", "tcp://localhost:1883",
                List.of(new GatewayServiceConfiguration.MqttInfluxDbRecording(
                        "Physical_Twin_VGR1", "gateway/+/VGRSystemModel_01/#", "extract-json-value", "extract-json-timestamp")),
                List.of(new GatewayServiceConfiguration.DtEventGroup("vgr1", "clone", "gateway/+/VGRSystemModel_01/",
                        null, List.of(new GatewayServiceConfiguration.DtEvent("vacuumSensRotEncoderCounter", "TokenLocation/vgr1/rot"))))));
        handler = new MqttMessageHandler(gatewayService, digitalShadow, dtEngine, bridge, Runnable::run);
    }

    private Message<String> message(boolean retained) {
        return MessageBuilder.withPayload(PAYLOAD)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, TOPIC)
                .setHeader(MqttHeaders.RECEIVED_RETAINED, retained)
                .build();
    }

    @Test
    void retainedMessageOnlyUpdatesTheShopfloorUi() {
        handler.handleMessage(message(true));

        verify(bridge).onGatewayMeasurement(eq(TOPIC), eq(2145L), any());
        verify(dtEngine, never()).dispatch(any());
        verify(digitalShadow, never()).processAndStoreMessage(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void liveMessageFeedsTheUiTheDtAndInfluxDb() {
        handler.handleMessage(message(false));

        verify(bridge).onGatewayMeasurement(eq(TOPIC), eq(2145L), any());
        verify(dtEngine).dispatch(any());
        verify(digitalShadow).processAndStoreMessage(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
    }
}
