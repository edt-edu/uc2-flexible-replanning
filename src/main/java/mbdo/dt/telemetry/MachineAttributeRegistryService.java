package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineAttributeUpdate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the latest value of every attribute of every shopfloor machine, so that a newly connected client starts from
 * the current machine states.
 */
@Service
public class MachineAttributeRegistryService {

    private record Key(String machineId, String attribute) {
    }

    private final Map<Key, MachineAttributeUpdate> latest = new ConcurrentHashMap<>();

    /** Records the attribute value and returns the update to push to the clients. */
    public Optional<MachineAttributeUpdate> record(String machineId, String attribute, Object value, Instant timestamp) {
        if (machineId == null || machineId.isBlank() || attribute == null || attribute.isBlank()) {
            return Optional.empty();
        }
        MachineAttributeUpdate update = new MachineAttributeUpdate(machineId, attribute, value, timestamp);
        latest.put(new Key(machineId, attribute), update);
        return Optional.of(update);
    }

    /** Latest value of every attribute of every machine. */
    public List<MachineAttributeUpdate> snapshots() {
        return List.copyOf(latest.values());
    }
}
