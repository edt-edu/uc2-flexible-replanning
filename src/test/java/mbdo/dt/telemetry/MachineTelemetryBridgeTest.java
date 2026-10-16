package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineAttributeUpdate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/** Every gateway measurement reaches the frontend as soon as it arrives, as the raw attribute value. */
class MachineTelemetryBridgeTest {

    private static final String VGR1 = "gateway/REN_1S1V_1C1V_1H1M_02/VGRSystemModel_01/";
    private static final String ROTATION = VGR1 + "vacuumSensRotEncoderCounter";

    private final MachineTelemetrySseService sseService = mock(MachineTelemetrySseService.class);
    private final MachineTelemetryBridge bridge = new MachineTelemetryBridge(
            new ShopfloorMachineIdResolver(), new MachineAttributeRegistryService(), sseService);

    @Test
    void eachMeasurementIsSentImmediately() {
        bridge.onGatewayMeasurement(ROTATION, 0L, 1_000L);
        bridge.onGatewayMeasurement(ROTATION, 2_400L, 1_005L);

        ArgumentCaptor<MachineAttributeUpdate> sent = ArgumentCaptor.forClass(MachineAttributeUpdate.class);
        verify(sseService, times(2)).broadcastMachineAttribute(sent.capture());
        List<MachineAttributeUpdate> updates = sent.getAllValues();
        assertEquals(new MachineAttributeUpdate("VGRSystemModel_01", "vacuumSensRotEncoderCounter", 2_400L,
                updates.get(1).timestamp()), updates.get(1));
        assertEquals(1_005L, updates.get(1).timestamp().toEpochMilli());
    }

    @Test
    void theGatewayMachineModelNameIsTheMachineId() {
        bridge.onGatewayMeasurement("gateway/REN_1S1V_1C1V_1H1M_02/HBSystemModel/inputBarrier", true, null);

        ArgumentCaptor<MachineAttributeUpdate> sent = ArgumentCaptor.forClass(MachineAttributeUpdate.class);
        verify(sseService).broadcastMachineAttribute(sent.capture());
        assertEquals("HBSystemModel", sent.getValue().machineId());
    }

    @Test
    void topicsOutsideTheGatewayAreIgnored() {
        bridge.onGatewayMeasurement("PLC/virtualmachines-plc-1/VacuumGripper/VacuumGripper01/x", 1, null);

        verify(sseService, never()).broadcastMachineAttribute(any());
    }
}
