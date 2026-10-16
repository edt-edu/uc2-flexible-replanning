package mbdo.dt.replanning.scada;

/** The SCADA refused or failed a replanning request. */
public class ScadaException extends RuntimeException {
    public ScadaException(String message) {
        super(message);
    }

    public ScadaException(String message, Throwable cause) {
        super(message, cause);
    }
}
