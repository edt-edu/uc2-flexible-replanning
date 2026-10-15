package mbdo.dt.replanning.scada;

import java.util.List;

/**
 * A machine of the SCADA mission extension (GET /api/replanning/machines).
 *
 * @param name            logical name in the mission extension (e.g. "REN_MISSION_01/VGR01", "ZoneCB")
 * @param physicalMachine SCADA machine it is mapped to, null for a zone
 * @param idle            whether it executes no command, null for a zone
 * @param activeMission   the mission it runs, null if none
 * @param activeState     the state of that mission, null if none
 */
public record ScadaMachine(
        String name,
        String physicalMachine,
        String type,
        Boolean idle,
        String activeMission,
        String activeState,
        List<ScadaMission> availableMissions
) {

    public boolean canRun(String mission) {
        return availableMissions != null && availableMissions.stream().anyMatch(m -> m.name().equals(mission));
    }

    public boolean hasState(String mission, String state) {
        return availableMissions != null && availableMissions.stream()
                .anyMatch(m -> m.name().equals(mission) && m.states() != null && m.states().contains(state));
    }

    public record ScadaMission(String name, String description, List<String> states, boolean running,
                               String activeState) {
    }
}
