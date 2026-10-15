package mbdo.dt.replanning.scada;

/**
 * The outcome of a command sent to a SCADA machine.
 *
 * @param status DONE, ABORTED_ERROR, ABORTED_TIMEOUT, INTERRUPTED (PLC feedback), TIMEOUT or BUSY
 */
public record ScadaCommandResult(
        String machine,
        String physicalMachine,
        String command,
        String commandId,
        String status,
        boolean done,
        long durationMs
) {
}
