package mbdo.dt.telemetry;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Maps a platform gateway topic ({@code gateway/<setup>/<MachineModel>/<attribute>}) onto the (machineId, attribute)
 * pair pushed to the shopfloor UI. The gateway machine model name is the machine id: the UI layout maps it onto its
 * own machine ids ({@code telemetryId}).
 */
@Component
public class ShopfloorMachineIdResolver {

    private static final String GATEWAY_ROOT = "gateway";

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

        return Optional.of(new MachineAttribute(parts[2], parts[3]));
    }
}
