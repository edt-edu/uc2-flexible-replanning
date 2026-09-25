package mbdo.dt.telemetry.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class VacuumGripperState extends AbstractMachineState {

    private static final Logger logger = LoggerFactory.getLogger(VacuumGripperState.class);

    private static final double MAX_VERTICAL_ENCODER_COUNTER = 1750d;
    private static final double MAX_ARM_ENCODER_COUNTER = 1970d;
    private static final double MAX_ROT_ENCODER_COUNTER = 3040d;

    // Visual arm length range is constrained by the VGR blue access-zone annulus.
    private static final double MIN_ARM_EXTENSION_MM = 165d;
    private static final double MAX_ARM_EXTENSION_MM = 315d;
    private static final double MAX_ARM_ROTATION_DEG = 360d;

    private double armExtension;
    private double armRotation;
    private double gripperExtension;
    private boolean gripperState;
    private boolean isArmElevated;
    private boolean isArmExtended;

    public VacuumGripperState(String machineId) {
        super(machineId);
        this.armExtension = MIN_ARM_EXTENSION_MM;
    }

    @Override
    public String machineType() {
        return "vacuum-gripper";
    }

    @Override
    protected void applyAttributeInternal(String normalizedAttribute, Object value) {
        Boolean boolValue = asBoolean(value);
        if (boolValue != null) {
            // vacuumActValve: the valve holds the vacuum, i.e. the gripper is holding a workpiece.
            if (normalizedAttribute.contains("gripperstate") || normalizedAttribute.contains("vacuumactvalve")) {
                gripperState = boolValue;
                return;
            }
            if (normalizedAttribute.contains("armelevated")) {
                isArmElevated = boolValue;
                return;
            }
            if (normalizedAttribute.contains("armextended")) {
                isArmExtended = boolValue;
                return;
            }
        }

        Double numberValue = asDouble(value);
        if (numberValue != null) {
            // Matches both the raw PLC names (vacuumSens*EncoderCounter) and the gateway aliases (*Encoder).
            if (normalizedAttribute.contains("verticalencoder")) {
                applyVerticalCounter(numberValue);
                return;
            }
            if (normalizedAttribute.contains("armencoder")) {
                applyArmCounter(numberValue);
                return;
            }
            if (normalizedAttribute.contains("rotencoder")) {
                applyRotationCounter(numberValue);
                return;
            }
            if (normalizedAttribute.contains("armextension")) {
                armExtension = clamp(numberValue, MIN_ARM_EXTENSION_MM, MAX_ARM_EXTENSION_MM);
                isArmExtended = armExtension > MIN_ARM_EXTENSION_MM;
                return;
            }
            if (normalizedAttribute.contains("armrotation")) {
                armRotation = numberValue;
                return;
            }
            if (normalizedAttribute.contains("gripperextension")) {
                gripperExtension = numberValue;
            }
        }
    }

    @Override
    public Map<String, Object> toState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("armExtension", armExtension);
        state.put("armRotation", armRotation);
        state.put("gripperExtension", gripperExtension);
        state.put("gripperState", gripperState);
        state.put("isArmElevated", isArmElevated);
        state.put("isArmExtended", isArmExtended);
        return withCommonState(state);
    }

    @Override
    protected void onInitializedFromFeedback(Map<String, Object> payload) {
        armRotation = 0d;
        armExtension = MIN_ARM_EXTENSION_MM;
        isArmElevated = true;
        isArmExtended = false;
        logger.info("[{}] VGR initialization defaults applied", machineId);
    }

    private void applyVerticalCounter(double rawCounter) {
        double clamped = clamp(rawCounter, 0d, MAX_VERTICAL_ENCODER_COUNTER);
        double ratio = clamped / MAX_VERTICAL_ENCODER_COUNTER;
        isArmElevated = ratio >= 0.5d;
    }

    private void applyArmCounter(double rawCounter) {
        double clamped = clamp(rawCounter, 0d, MAX_ARM_ENCODER_COUNTER);
        double ratio = clamped / MAX_ARM_ENCODER_COUNTER;
        armExtension = MIN_ARM_EXTENSION_MM + (ratio * (MAX_ARM_EXTENSION_MM - MIN_ARM_EXTENSION_MM));
        isArmExtended = armExtension > MIN_ARM_EXTENSION_MM;
    }

    private void applyRotationCounter(double rawCounter) {
        double clamped = clamp(rawCounter, 0d, MAX_ROT_ENCODER_COUNTER);
        // SVG positive rotation is clockwise, so invert sign to map counter growth to CCW.
        armRotation = -((clamped / MAX_ROT_ENCODER_COUNTER) * MAX_ARM_ROTATION_DEG);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
