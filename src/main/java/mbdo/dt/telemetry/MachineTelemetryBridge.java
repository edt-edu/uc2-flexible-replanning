package mbdo.dt.telemetry;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Feeds the shopfloor machine-state registry/SSE stream from the platform gateway topics,
 * decoupled from {@link mbdo.dt.orchestrator.DtEngine}'s anomaly/replanning simulation.
 */
@Component
public class MachineTelemetryBridge {

    private final ShopfloorMachineIdResolver machineIdResolver;
    private final MachineStateRegistryService machineStateRegistryService;
    private final MachineTelemetrySseService machineTelemetrySseService;

    public MachineTelemetryBridge(
            ShopfloorMachineIdResolver machineIdResolver,
            MachineStateRegistryService machineStateRegistryService,
            MachineTelemetrySseService machineTelemetrySseService
    ) {
        this.machineIdResolver = machineIdResolver;
        this.machineStateRegistryService = machineStateRegistryService;
        this.machineTelemetrySseService = machineTelemetrySseService;
    }

    public void onGatewayMeasurement(String gatewayTopic, Object value, Long timestampMillis) {
        machineIdResolver.resolve(gatewayTopic).ifPresent(machineAttribute -> {
            Instant timestamp = timestampMillis != null
                    ? Instant.ofEpochMilli(timestampMillis)
                    : Instant.now();

            machineStateRegistryService.applyAttribute(
                    machineAttribute.machineId(),
                    machineAttribute.attribute(),
                    value,
                    timestamp
            ).ifPresent(machineTelemetrySseService::broadcastMachineUpdate);
        });
    }
}
