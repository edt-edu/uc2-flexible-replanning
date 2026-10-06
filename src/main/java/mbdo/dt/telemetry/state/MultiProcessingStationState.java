package mbdo.dt.telemetry.state;

import java.util.LinkedHashMap;
import java.util.Map;

public class MultiProcessingStationState extends AbstractMachineState {

    private boolean sawEnabled;
    private double sawRotation;
    private boolean sliderExtended;
    private boolean sensorIn;
    private boolean sensorOut;

    public MultiProcessingStationState(String machineId) {
        super(machineId);
    }

    @Override
    public String machineType() {
        return "multi-processing-station";
    }

    @Override
    protected void applyAttributeInternal(String normalizedAttribute, Object value) {
        Boolean boolValue = asBoolean(value);
        if (boolValue != null) {
            if (normalizedAttribute.contains("sawenabled")) {
                sawEnabled = boolValue;
                return;
            }
            if (normalizedAttribute.contains("sliderextended")) {
                sliderExtended = boolValue;
                return;
            }
            // Platform light barriers, active-low (false = token in the beam): inverted so the UI shows
            // true = triggered. The input is the oven feeder, where VGR2 places the token.
            if (normalizedAttribute.equals("multiprocessingsensoven")) {
                sensorIn = !boolValue;
                return;
            }
            if (normalizedAttribute.equals("multiprocessingsensendconveyor")) {
                sensorOut = !boolValue;
                return;
            }
            // Gateway aliases from configuration.yml: sensor_MPS_in / sensor_MPS_out.
            if (normalizedAttribute.equals("sensormpsin")) {
                sensorIn = boolValue;
                return;
            }
            if (normalizedAttribute.equals("sensormpsout")) {
                sensorOut = boolValue;
                return;
            }
        }

        Double numberValue = asDouble(value);
        if (numberValue != null && normalizedAttribute.contains("sawrotation")) {
            sawRotation = numberValue;
        }
    }

    @Override
    public Map<String, Object> toState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sawEnabled", sawEnabled);
        state.put("sawRotation", sawRotation);
        state.put("sliderExtended", sliderExtended);
        state.put("sensorIn", sensorIn);
        state.put("sensorOut", sensorOut);
        return withCommonState(state);
    }
}
