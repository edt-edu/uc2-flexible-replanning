package mbdo.dt.telemetry;

import mbdo.dt.domain.DataRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Feeds the shopfloor machine-state registry/SSE stream from the existing MQTT pipeline,
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

    public void onDataRequest(DataRequest request) {
        if (request == null) {
            return;
        }

        machineIdResolver.resolve(request).ifPresent(machineAttribute -> {
            Instant timestamp = request.timestamp() != null
                    ? Instant.ofEpochMilli(request.timestamp())
                    : Instant.now();

            machineStateRegistryService.applyAttribute(
                    machineAttribute.machineId(),
                    machineAttribute.attribute(),
                    request.value(),
                    timestamp
            ).ifPresent(machineTelemetrySseService::broadcastMachineUpdate);
        });
    }
}
