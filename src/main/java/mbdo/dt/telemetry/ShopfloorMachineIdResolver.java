package mbdo.dt.telemetry;

import mbdo.dt.domain.DataRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Maps an MQTT-derived {@link DataRequest} onto the shopfloor (machineId, attribute) pair
 * expected by {@link mbdo.dt.telemetry.state.MachineStateFactory}.
 *
 * This is the extension point to update when new {@code dtEventGroups} mapping rules are
 * added to configuration.yml for machines/attributes not covered yet.
 */
@Component
public class ShopfloorMachineIdResolver {

    /** SysML model names (DataRequest.modelName()) that map 1:1 to a single machine. */
    private static final Map<String, String> MODEL_NAME_TO_MACHINE_ID = Map.of(
            "REN_1V_01_VGRSystemModel", "VGRSystemModel_01"
    );

    /** Short machine codes embedded in compound attribute names, e.g. "cb.conveyorSensFeed". */
    private static final Map<String, String> SHORT_CODE_TO_MACHINE_ID = Map.ofEntries(
            Map.entry("cb", "CBSystemModel"),
            Map.entry("sl", "SLSystemModel"),
            Map.entry("mpo", "MPSystemModel"),
            Map.entry("mps", "MPSystemModel"),
            Map.entry("vgr1", "VGRSystemModel_01"),
            Map.entry("vgr2", "VGRSystemModel_02"),
            Map.entry("hbw", "HBWSystemModel")
    );

    public record MachineAttribute(String machineId, String attribute) {
    }

    public Optional<MachineAttribute> resolve(DataRequest request) {
        if (request == null || request.attributeName() == null) {
            return Optional.empty();
        }

        String modelMachineId = MODEL_NAME_TO_MACHINE_ID.get(request.modelName());
        if (modelMachineId != null) {
            return Optional.of(new MachineAttribute(modelMachineId, request.attributeName()));
        }

        String attributeName = request.attributeName();
        int dotIndex = attributeName.indexOf('.');
        if (dotIndex > 0) {
            String shortCode = attributeName.substring(0, dotIndex);
            String machineId = SHORT_CODE_TO_MACHINE_ID.get(shortCode);
            if (machineId != null) {
                return Optional.of(new MachineAttribute(machineId, attributeName.substring(dotIndex + 1)));
            }
        }

        return Optional.empty();
    }
}
