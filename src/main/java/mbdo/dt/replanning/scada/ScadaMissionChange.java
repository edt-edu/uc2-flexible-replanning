package mbdo.dt.replanning.scada;

/**
 * A change of the mission running on a SCADA machine: the mission and state it left, the ones it runs now.
 */
public record ScadaMissionChange(
        String machine,
        String previousMission,
        String previousState,
        String mission,
        String state
) {
}
