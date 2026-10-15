package mbdo.dt.telemetry.state;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * At each named position of a VGR, the arm drawn on the shopfloor SVG must reach the machine element of that position
 * (checked on {@code frontend/public/assets/shopfloor.svg}, the drawing the UI loads).
 */
class VacuumGripperArmDisplayTest {

    private static final File SVG = new File("frontend/public/assets/shopfloor.svg");
    private static final double PIVOT_X = 214;
    private static final double PIVOT_Y = 105;
    /** The end effector (22 px wide) ends at the arm end: its center is 11 px before it. */
    private static final double END_EFFECTOR_HALF_WIDTH = 11;

    private static Document svg;

    @BeforeAll
    static void loadSvg() throws Exception {
        svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(SVG);
    }

    @TestFactory
    Stream<DynamicTest> theArmReachesTheDrawnMachineAtEachNamedPosition() {
        return Stream.of("VGRSystemModel_01", "VGRSystemModel_02").flatMap(vgr ->
                VacuumGripperArmCalibration.forMachine(vgr).orElseThrow().anchors().stream().map(anchor ->
                        DynamicTest.dynamicTest(vgr + " " + anchor.name(), () -> assertReaches(vgr, anchor))));
    }

    private static void assertReaches(String vgr, VacuumGripperArmCalibration.Anchor anchor) {
        VacuumGripperState state = new VacuumGripperState(vgr);
        state.applyAttribute("vacuumSensRotEncoderCounter", anchor.rotationCounts(), Instant.now());
        state.applyAttribute("vacuumSensArmEncoderCounter", anchor.horizontalCounts(), Instant.now());
        double angle = (double) state.toState().get("armRotation");
        double length = (double) state.toState().get("armExtension");

        double[] machine = transformOf(byId(vgr));
        double[] pivot = apply(machine, PIVOT_X, PIVOT_Y);
        double a = Math.toRadians(angle);
        double reach = length - END_EFFECTOR_HALF_WIDTH;
        double[] endEffector = apply(machine, PIVOT_X + reach * Math.cos(a), PIVOT_Y + reach * Math.sin(a));
        double[] target = center(byId(anchor.svgTarget()));

        double targetDistance = Math.hypot(target[0] - pivot[0], target[1] - pivot[1]) + END_EFFECTOR_HALF_WIDTH;
        if (targetDistance <= VacuumGripperArmCalibration.MAX_ARM_LENGTH) {
            double miss = Math.hypot(endEffector[0] - target[0], endEffector[1] - target[1]);
            assertTrue(miss < 2, anchor.name() + ": end effector " + miss + " px away from " + anchor.svgTarget());
        } else {
            // beyond the reach ring: the arm is drawn at full length, pointing at the target
            assertEquals(VacuumGripperArmCalibration.MAX_ARM_LENGTH, length, 1e-9);
            double towardsTarget = Math.atan2(target[1] - pivot[1], target[0] - pivot[0]);
            double towardsEndEffector = Math.atan2(endEffector[1] - pivot[1], endEffector[0] - pivot[0]);
            double error = Math.toDegrees(Math.abs(Math.atan2(Math.sin(towardsTarget - towardsEndEffector),
                    Math.cos(towardsTarget - towardsEndEffector))));
            assertTrue(error < 0.5, anchor.name() + ": arm " + error + " degrees off " + anchor.svgTarget());
        }
    }

    @Test
    void theArmOnlyGrowsAsItExtends() {
        VacuumGripperArmCalibration vgr2 = VacuumGripperArmCalibration.forMachine("VGRSystemModel_02").orElseThrow();
        double previous = 0;
        for (int horizontal = 0; horizontal <= 1970; horizontal += 10) {
            double length = vgr2.length(2050, horizontal);
            assertTrue(length >= previous, "length decreases at horizontal " + horizontal);
            previous = length;
        }
        assertEquals(VacuumGripperArmCalibration.MIN_ARM_LENGTH, vgr2.length(2050, 0), 1e-9);
    }

    @Test
    void theArmIsAtZeroDegreesAtZeroRotationCounts() {
        for (String vgr : List.of("VGRSystemModel_01", "VGRSystemModel_02")) {
            VacuumGripperArmCalibration calibration = VacuumGripperArmCalibration.forMachine(vgr).orElseThrow();
            assertEquals(0d, calibration.angle(0), 1e-9, vgr);
            assertEquals(0d, calibration.angle(-100), 1e-9, vgr + ": below the axis range");
        }
    }

    @Test
    void theRotationStopsAtTheEndOfTheAxis() {
        for (String vgr : List.of("VGRSystemModel_01", "VGRSystemModel_02")) {
            VacuumGripperArmCalibration calibration = VacuumGripperArmCalibration.forMachine(vgr).orElseThrow();
            double atMax = calibration.angle(VacuumGripperArmCalibration.MAX_ROTATION_COUNTS);
            assertEquals(atMax, calibration.angle(4000), 1e-9, vgr);
            assertTrue(Math.abs(atMax) > Math.abs(calibration.angle(2900)), vgr + ": still turning before 2990");
        }
    }

    @Test
    void afterSetupTheArmIsDrawnAtTheReferencePosition() {
        VacuumGripperState state = new VacuumGripperState("VGRSystemModel_01");
        state.applyAttribute("vacuumSensRotEncoderCounter", 2400, Instant.now());
        state.applyAttribute("vacuumSensArmEncoderCounter", 500, Instant.now());

        state.applyAttribute("machineFeedback", "{\"status\":\"INITIALIZED_IDLE\"}", Instant.now());

        VacuumGripperArmCalibration vgr1 = VacuumGripperArmCalibration.forMachine("VGRSystemModel_01").orElseThrow();
        assertEquals(vgr1.angle(0), (double) state.toState().get("armRotation"), 1e-9);
        assertEquals(VacuumGripperArmCalibration.MIN_ARM_LENGTH, (double) state.toState().get("armExtension"), 1e-9);
    }

    // ------------------------------------------------------------------ SVG geometry

    private static Element byId(String id) {
        NodeList all = svg.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        throw new AssertionError("no element " + id + " in " + SVG);
    }

    /** Center of a rect, in map coordinates. */
    private static double[] center(Element rect) {
        double x = number(rect, "x") + number(rect, "width") / 2;
        double y = number(rect, "y") + number(rect, "height") / 2;
        return apply(transformOf(rect), x, y);
    }

    private static double number(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        return value.isEmpty() ? 0 : Double.parseDouble(value);
    }

    /** Combined transform of an element and its ancestors, as an SVG matrix (a, b, c, d, e, f). */
    private static double[] transformOf(Element element) {
        double[] matrix = {1, 0, 0, 1, 0, 0};
        for (Node node = element; node instanceof Element current; node = node.getParentNode()) {
            matrix = multiply(parse(current.getAttribute("transform")), matrix);
        }
        return matrix;
    }

    private static final Pattern TRANSFORM = Pattern.compile("(\\w+)\\s*\\(([^)]*)\\)");

    private static double[] parse(String transform) {
        double[] matrix = {1, 0, 0, 1, 0, 0};
        Matcher matcher = TRANSFORM.matcher(transform == null ? "" : transform);
        while (matcher.find()) {
            List<Double> v = Stream.of(matcher.group(2).trim().split("[ ,]+")).map(Double::parseDouble).toList();
            double[] next = switch (matcher.group(1)) {
                case "matrix" -> new double[]{v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(5)};
                case "translate" -> new double[]{1, 0, 0, 1, v.get(0), v.size() > 1 ? v.get(1) : 0};
                case "rotate" -> {
                    double r = Math.toRadians(v.get(0));
                    double[] rotation = {Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0};
                    yield v.size() == 3
                            ? multiply(multiply(new double[]{1, 0, 0, 1, v.get(1), v.get(2)}, rotation),
                            new double[]{1, 0, 0, 1, -v.get(1), -v.get(2)})
                            : rotation;
                }
                default -> throw new AssertionError("unsupported transform " + transform);
            };
            matrix = multiply(matrix, next);
        }
        return matrix;
    }

    private static double[] multiply(double[] m, double[] n) {
        return new double[]{
                m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
                m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
                m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]};
    }

    private static double[] apply(double[] m, double x, double y) {
        return new double[]{m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
    }
}
