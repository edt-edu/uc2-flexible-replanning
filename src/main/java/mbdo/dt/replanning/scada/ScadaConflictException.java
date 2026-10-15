package mbdo.dt.replanning.scada;

/**
 * The SCADA machine doesn't run the mission or state the request expected: it moved on since it was read.
 */
public class ScadaConflictException extends ScadaException {
    public ScadaConflictException(String message) {
        super(message);
    }
}
