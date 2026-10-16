package mbdo.dt.telemetry.dto;

import java.time.Instant;

/**
 * Raw controller attribute of a machine pushed to the frontend through SSE, as received from the platform gateway.
 * The frontend draws it with the {@code @mbdo/machines-svg-map} library, which knows how each attribute is displayed.
 *
 * @param machineId gateway machine model name, e.g. {@code VGRSystemModel_01}
 * @param attribute attribute name, e.g. {@code vacuumSensRotEncoderCounter}
 * @param value     raw value (boolean, number, string or JSON object)
 * @param timestamp measurement time
 */
public record MachineAttributeUpdate(
        String machineId,
        String attribute,
        Object value,
        Instant timestamp
) {
}
