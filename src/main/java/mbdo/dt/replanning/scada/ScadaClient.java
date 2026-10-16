package mbdo.dt.replanning.scada;

import java.util.List;
import java.util.Map;

/**
 * What the replanning asks the SCADA (its REST API /api/replanning, see the FactorySCADA ReplanningController).
 */
public interface ScadaClient {

    /** Every machine of the mission extension, the missions it can run, and the mission and state it runs. */
    List<ScadaMachine> machines();

    /**
     * Replaces the mission running on {@code machine}, the new one starting in {@code state} (from its initial state
     * if null).
     *
     * @param expectedMission if not null, the SCADA refuses unless this mission runs on the machine ("" for none)
     * @param expectedState   if not null, the SCADA refuses unless the running mission is in this state
     * @throws ScadaConflictException if the machine doesn't run the expected mission or state
     */
    ScadaMissionChange swapMission(String machine, String mission, String state, String expectedMission,
                                   String expectedState);

    /**
     * Stops the mission running on {@code machine}.
     *
     * @throws ScadaConflictException if the machine doesn't run the expected mission or state
     */
    ScadaMissionChange stopMission(String machine, String expectedMission, String expectedState);

    /**
     * Sends a command to {@code machine}, which must run no mission, and waits for its feedback.
     *
     * @param parameters as in a SCADA command payload ({"passableType": "NAMEDPOSITION", "passable": {...}})
     */
    ScadaCommandResult executeCommand(String machine, String command, List<Map<String, Object>> parameters,
                                      long timeoutMs);
}
