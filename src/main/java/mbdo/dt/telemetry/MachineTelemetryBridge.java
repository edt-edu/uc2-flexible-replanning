package mbdo.dt.telemetry;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Feeds the shopfloor SSE stream with the raw platform gateway measurements,
 * decoupled from {@link mbdo.dt.orchestrator.DtEngine}'s anomaly/replanning simulation.
 */
@Component
public class MachineTelemetryBridge {

    private final ShopfloorMachineIdResolver machineIdResolver;
    private final MachineAttributeRegistryService machineAttributeRegistryService;
    private final MachineTelemetrySseService machineTelemetrySseService;

    public MachineTelemetryBridge(
            ShopfloorMachineIdResolver machineIdResolver,
            MachineAttributeRegistryService machineAttributeRegistryService,
            MachineTelemetrySseService machineTelemetrySseService
    ) {
        this.machineIdResolver = machineIdResolver;
        this.machineAttributeRegistryService = machineAttributeRegistryService;
        this.machineTelemetrySseService = machineTelemetrySseService;
    }

    public void onGatewayMeasurement(String gatewayTopic, Object value, Long timestampMillis) {
        machineIdResolver.resolve(gatewayTopic).ifPresent(machineAttribute -> {
            Instant timestamp = timestampMillis != null
                    ? Instant.ofEpochMilli(timestampMillis)
                    : Instant.now();

            machineAttributeRegistryService.record(
                    machineAttribute.machineId(),
                    machineAttribute.attribute(),
                    value,
                    timestamp
            ).ifPresent(machineTelemetrySseService::broadcastMachineAttribute);
        });
    }
}
