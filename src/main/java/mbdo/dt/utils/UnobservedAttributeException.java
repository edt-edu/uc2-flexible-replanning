package mbdo.dt.utils;

/**
 * A guard references an attribute for which no value has been observed yet (e.g. right after startup, before the
 * sensor first changed). The guard is simply not satisfied yet: this is not an evaluation error.
 */
public class UnobservedAttributeException extends IllegalArgumentException {

    public UnobservedAttributeException(String attribute) {
        super("Cannot resolve '" + attribute + "' - no observed value for this attribute yet.");
    }
}
