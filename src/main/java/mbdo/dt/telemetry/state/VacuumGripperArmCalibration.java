package mbdo.dt.telemetry.state;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Maps a VGR's encoder counts to how its arm is drawn on the shopfloor SVG, so that the arm points at the drawn
 * machine when the gripper is at one of its named positions (the positions the SCADA missions send by name).
 *
 * <p>Each anchor is a named position of the PLC (rotation and horizontal encoder counts) and the arm angle / length
 * that put the end effector on the SVG element of that position. Between anchors the angle is interpolated linearly on
 * the rotation counts; outside them it continues with the average slope. The drawing is not to scale, so the arm length
 * is not interpolated on the horizontal counts alone: at a rotation, the anchor length (interpolated on the rotation)
 * is reached at the anchor's horizontal counts, so the arm only grows as it extends and is exact on each anchor.
 *
 * <p>The counts are those of the named positions in {@code dt-setups} (REN {@code RevPi0x/python/RevPi_Controller.py},
 * virtual {@code PLC1/config.yml}); the angles and lengths come from {@code frontend/public/assets/shopfloor.svg}
 * (arm pivot at local (214, 105), end effector center 11 px before the arm end). Update both when a position is
 * recalibrated or a machine moves on the SVG.
 */
final class VacuumGripperArmCalibration {

    static final double MIN_ARM_LENGTH = 165d;
    static final double MAX_ARM_LENGTH = 315d;

    /**
     * A named position and how the arm is drawn there.
     *
     * @param svgTarget id of the SVG element the end effector is drawn on at this position
     * @param angle     arm rotation, in degrees, of the {@code vgrArmRotation} group (SVG: positive is clockwise)
     * @param length    arm length in px, from the pivot (at most {@link #MAX_ARM_LENGTH}, the reach ring)
     */
    record Anchor(String name, double rotationCounts, double horizontalCounts, String svgTarget, double angle,
                  double length) {
    }

    private static final Map<String, VacuumGripperArmCalibration> BY_MACHINE_ID = Map.of(
            // VGR1 (I1VacuumGripper01, RevPi01): base rotated by 90 degrees, pivot at (555, 1234)
            "VGRSystemModel_01", new VacuumGripperArmCalibration(List.of(
                    new Anchor("ALT_CB", 1360, 1900, "sensorSwapCBSystemModel", -152.0, 298.6),
                    new Anchor("CB", 1860, 1460, "sensorFeedCBSystemModel", -192.2, 270.9),
                    // drawn 316 px away, beyond the reach ring
                    new Anchor("SL_OUTPUT_BLUE", 2175, 1500,
                            "sortingLineSensBlueLightBarrierSLSystemModel", -220.3, 315.0),
                    new Anchor("SL_OUTPUT_RED", 2270, 970,
                            "sortingLineSensRedLightBarrierSLSystemModel", -231.3, 283.1),
                    new Anchor("SL_OUTPUT_WHITE", 2400, 500,
                            "sortingLineSensWhiteLightBarrierSLSystemModel", -240.7, 237.6),
                    new Anchor("SL_INPUT", 2900, 1670,
                            "sortingLineSensInputLightBarrierSLSystemModel", -289.1, 311.6))),
            // VGR2 (I1VacuumGripper02, RevPi02): base not rotated, pivot at (707, 729)
            "VGRSystemModel_02", new VacuumGripperArmCalibration(List.of(
                    new Anchor("HBW", 1010, 740, "inputBarrierHBWSystemModel", -109.2, 272.1),
                    // drawn 369 px away, beyond the reach ring
                    new Anchor("MPS_OUTPUT", 1480, 1720, "sensorOutMPSystemModel", -145.9, 315.0),
                    // drawn 339 px away, beyond the reach ring
                    new Anchor("MPS_INPUT", 2050, 1890, "sensorInMPSystemModel", -197.9, 315.0),
                    new Anchor("ALT_CB", 2650, 1350, "sensorSwapCBSystemModel", -266.1, 262.6))));

    private final List<Anchor> anchors;
    private final double averageSlope;

    private VacuumGripperArmCalibration(List<Anchor> anchors) {
        this.anchors = anchors.stream().sorted(Comparator.comparingDouble(Anchor::rotationCounts)).toList();
        Anchor first = this.anchors.get(0);
        Anchor last = this.anchors.get(this.anchors.size() - 1);
        this.averageSlope = (last.angle() - first.angle()) / (last.rotationCounts() - first.rotationCounts());
    }

    static Optional<VacuumGripperArmCalibration> forMachine(String machineId) {
        return Optional.ofNullable(BY_MACHINE_ID.get(machineId));
    }

    List<Anchor> anchors() {
        return anchors;
    }

    /** Arm rotation, in degrees, at these rotation encoder counts. */
    double angle(double rotationCounts) {
        Anchor first = anchors.get(0);
        Anchor last = anchors.get(anchors.size() - 1);
        if (rotationCounts <= first.rotationCounts()) {
            return first.angle() + (rotationCounts - first.rotationCounts()) * averageSlope;
        }
        if (rotationCounts >= last.rotationCounts()) {
            return last.angle() + (rotationCounts - last.rotationCounts()) * averageSlope;
        }
        return interpolate(rotationCounts, Anchor::angle);
    }

    /** Arm length, in px, at these rotation and horizontal encoder counts. */
    double length(double rotationCounts, double horizontalCounts) {
        double anchorHorizontal = interpolate(rotationCounts, Anchor::horizontalCounts);
        double anchorLength = interpolate(rotationCounts, Anchor::length);
        double ratio = anchorHorizontal > 0 ? horizontalCounts / anchorHorizontal : 0d;
        double length = MIN_ARM_LENGTH + ratio * (anchorLength - MIN_ARM_LENGTH);
        return Math.max(MIN_ARM_LENGTH, Math.min(MAX_ARM_LENGTH, length));
    }

    /** Linear interpolation of {@code value} between the anchors around {@code rotationCounts}, clamped at the ends. */
    private double interpolate(double rotationCounts, ToDoubleFunction<Anchor> value) {
        Anchor previous = anchors.get(0);
        if (rotationCounts <= previous.rotationCounts()) {
            return value.applyAsDouble(previous);
        }
        for (Anchor next : anchors.subList(1, anchors.size())) {
            if (rotationCounts <= next.rotationCounts()) {
                double t = (rotationCounts - previous.rotationCounts())
                        / (next.rotationCounts() - previous.rotationCounts());
                return value.applyAsDouble(previous) + t * (value.applyAsDouble(next) - value.applyAsDouble(previous));
            }
            previous = next;
        }
        return value.applyAsDouble(anchors.get(anchors.size() - 1));
    }
}
