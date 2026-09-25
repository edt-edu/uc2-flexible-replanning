package mbdo.dt.telemetry.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Machine state snapshot pushed to the frontend through SSE.
 */
public class MachineStateUpdateResponse {

    private final String machineId;
    private final String machineType;
    private final Map<String, Object> state;
    private final Instant timestamp;

    public MachineStateUpdateResponse(String machineId, String machineType, Map<String, Object> state,
            Instant timestamp) {
        this.machineId = machineId;
        this.machineType = machineType;
        this.state = state;
        this.timestamp = timestamp;
    }

    public String getMachineId() {
        return machineId;
    }

    public String getMachineType() {
        return machineType;
    }

    public Map<String, Object> getState() {
        return state;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
