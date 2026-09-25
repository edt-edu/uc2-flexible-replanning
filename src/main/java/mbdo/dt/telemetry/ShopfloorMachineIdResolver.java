package mbdo.dt.telemetry;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Maps a platform gateway topic ({@code gateway/<setup>/<MachineModel>/<attribute>}) onto the shopfloor
 * (machineId, attribute) pair expected by {@link mbdo.dt.telemetry.state.MachineStateFactory}.
 *
 * The gateway machine model names are used as machine ids as-is, except for the aliases below.
 */
@Component
public class ShopfloorMachineIdResolver {

    private static final String GATEWAY_ROOT = "gateway";

    /** Gateway machine model names that differ from the machine ids used by the frontend. */
    private static final Map<String, String> GATEWAY_MODEL_TO_MACHINE_ID = Map.of(
            "HBSystemModel", "HBWSystemModel"
    );

    public record MachineAttribute(String machineId, String attribute) {
    }

    public Optional<MachineAttribute> resolve(String gatewayTopic) {
        if (gatewayTopic == null) {
            return Optional.empty();
        }

        String[] parts = gatewayTopic.split("/");
        if (parts.length != 4 || !GATEWAY_ROOT.equals(parts[0]) || parts[2].isBlank() || parts[3].isBlank()) {
            return Optional.empty();
        }

        String machineId = GATEWAY_MODEL_TO_MACHINE_ID.getOrDefault(parts[2], parts[2]);
        return Optional.of(new MachineAttribute(machineId, parts[3]));
    }
}
