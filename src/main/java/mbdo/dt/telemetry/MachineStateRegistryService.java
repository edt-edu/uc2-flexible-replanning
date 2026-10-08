package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineStateUpdateResponse;
import mbdo.dt.telemetry.state.MachineState;
import mbdo.dt.telemetry.state.MachineStateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the latest known state of every shopfloor machine, keyed by machineId,
 * and builds the full snapshot to push to the frontend whenever an attribute changes.
 */
@Service
public class MachineStateRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(MachineStateRegistryService.class);

    private final Map<String, MachineState> machineStates = new ConcurrentHashMap<>();

    public Optional<MachineStateUpdateResponse> applyAttribute(
            String machineId,
            String attribute,
            Object value,
            Instant timestamp
    ) {
        if (machineId == null || machineId.isBlank()) {
            return Optional.empty();
        }

        MachineState machineState = machineStates.computeIfAbsent(machineId, id -> {
            logger.info("[StateRegistry] Creating new machine state - machineId: {}", id);
            return MachineStateFactory.create(id);
        });

        machineState.applyAttribute(attribute, value, timestamp);

        if (!machineState.isReadyForFrontend()) {
            return Optional.empty();
        }

        return Optional.of(toResponse(machineState));
    }

    /** Latest state of this machine, if the frontend can display it. */
    public Optional<MachineStateUpdateResponse> snapshot(String machineId) {
        return Optional.ofNullable(machineId == null ? null : machineStates.get(machineId))
                .filter(MachineState::isReadyForFrontend)
                .map(MachineStateRegistryService::toResponse);
    }

    /** Latest state of every machine the frontend can display, e.g. to initialize a newly connected client. */
    public List<MachineStateUpdateResponse> snapshots() {
        return machineStates.values().stream()
                .filter(MachineState::isReadyForFrontend)
                .map(MachineStateRegistryService::toResponse)
                .toList();
    }

    private static MachineStateUpdateResponse toResponse(MachineState machineState) {
        return new MachineStateUpdateResponse(
                machineState.machineId(),
                machineState.machineType(),
                machineState.toState(),
                machineState.updatedAt()
        );
    }
}
