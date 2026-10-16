package mbdo.dt.services.impl;

import mbdo.dt.models.BehavioralElementExtractor;
import mbdo.dt.models.ConstraintExtractor;
import mbdo.dt.models.DatabaseSchemaDeriver;
import mbdo.dt.models.RuntimeInterpreter;
import mbdo.dt.models.RuntimeInterpreter.FoundValue;
import mbdo.dt.models.RuntimeInterpreter.GuardClauseEvaluation;
import mbdo.dt.models.RuntimeInterpreter.GuardClauseEvaluation.Result;
import mbdo.dt.models.RuntimeInterpreter.GuardTransitionEvaluation;
import mbdo.dt.models.StateMachineSelector;
import mbdo.dt.models.StructuralDefinitionExtractor;
import mbdo.dt.models.SysMLV2Parser;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;
import mbdo.dt.services.AnomalyDetection.EvaluationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The TokenLocation model from src/main/resources, with a controllable clock. */
@SpringJUnitConfig(classes = {SysMLV2Parser.class, StructuralDefinitionExtractor.class, BehavioralElementExtractor.class,
        ConstraintExtractor.class, DatabaseSchemaDeriver.class, StateMachineSelector.class, RuntimeInterpreter.class})
class AnomalyDetectionTimeCheckTest {

    @Autowired
    private SysMLV2Parser parser;
    @Autowired
    private StateMachineSelector selector;
    @Autowired
    private RuntimeInterpreter interpreter;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<AnomalyEvaluationResult> reactedTo = new ArrayList<>();
    private final Map<String, Object> values = new HashMap<>();
    private AnomalyDetectionImpl detection;

    @BeforeEach
    void setUp() throws IOException {
        List<SysMLV2Parser.ParsedSysMLModel> models = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:models/**/*.sysml")) {
            models.add(parser.loadModel(resource));
        }
        detection = new AnomalyDetectionImpl(clock::get, interpreter, selector,
                (request, result, latest) -> reactedTo.add(result), "TokenLocation");
        detection.activateModels(models);
    }

    private AnomalyEvaluationResult observe(String attribute, Object value) {
        values.put(attribute, value);
        values.put("timestamp", clock.get());
        return detection.evaluateObservation(new HashMap<>(values));
    }

    @Test
    void timeCheckWithoutAnyObservationJustWaits() {
        AnomalyEvaluationResult result = detection.checkTimeTransitions();

        assertEquals(EvaluationStatus.WAITING_FOR_MATCH, result.status());
        assertEquals("WaitingForTokenAtSortingLineInput", detection.getCurrentState());
    }

    @Test
    void timeCheckDetectsAStateThatOverrunsItsDurationLimit() {
        // token dropped at the conveyor belt feed (Token_t0 .. Token_t3)
        observe("sl.sensor_SL_in", false);
        observe("sl.sensor_SL_in", true);
        values.put("sl.sensor_SL_white", true);
        values.put("sl.sensor_SL_red", true);
        observe("sl.sensor_SL_blue", false);
        values.put("vgr1.rotEncoder", 2175); // VGR1 at SL_OUTPUT_BLUE
        values.put("vgr1.armEncoder", 1500);
        values.put("vgr1.vacuumActCompressorOn", true);
        values.put("vgr1.vacuumActValve", true);
        observe("sl.sensor_SL_blue", true);
        values.put("vgr2.vacuumActCompressorOn", false);
        values.put("vgr2.vacuumActValve", false);
        observe("cb.conveyorSensFeed", false);
        assertEquals("TokenDroppedOffAtConveyorBeltFeed", detection.getCurrentState());

        clock.addAndGet(24_000);
        assertEquals(EvaluationStatus.WAITING_FOR_MATCH, detection.checkTimeTransitions().status());

        clock.addAndGet(2_000); // past the 25 s of ConveyorBeltFeedDepartureLimit
        AnomalyEvaluationResult result = detection.checkTimeTransitions();
        assertEquals(EvaluationStatus.ANOMALY_DETECTED, result.status());
        assertEquals("ConveyorBeltFeedDepartureLimit", result.transitionName());
        assertEquals(result, reactedTo.get(reactedTo.size() - 1));
    }

    /** Up to the token dropped at the conveyor belt feed (Token_t0 .. Token_t3). */
    private void tokenDroppedAtTheBeltFeed() {
        tokenInTheBlueChuteAndVgr1Gripping();
        values.put("vgr1.rotEncoder", 2175); // VGR1 at SL_OUTPUT_BLUE
        values.put("vgr1.armEncoder", 1500);
        observe("sl.sensor_SL_blue", true);
        values.put("vgr2.vacuumActCompressorOn", false);
        values.put("vgr2.vacuumActValve", false);
        values.put("cb.conveyorSensSwap", true);
        observe("cb.conveyorSensFeed", false);
        assertEquals("TokenDroppedOffAtConveyorBeltFeed", detection.getCurrentState());
    }

    @Test
    void aTokenThatStaysAtTheSwapViolatesTheSwapDepartureLimit() {
        tokenDroppedAtTheBeltFeed();
        observe("cb.conveyorSensFeed", true);
        assertEquals("TokenDroppedOffAtConveyorBeltFeed", detection.getCurrentState(), "carried by the belt");
        clock.addAndGet(4_000);
        observe("cb.conveyorSensSwap", false);
        assertEquals("TokenAtConveyorBeltSwap", detection.getCurrentState());

        clock.addAndGet(19_000);
        assertEquals(EvaluationStatus.WAITING_FOR_MATCH, detection.checkTimeTransitions().status());

        clock.addAndGet(2_000); // past the 20 s of ConveyorBeltSwapDepartureLimit: VGR2 didn't pick it
        AnomalyEvaluationResult result = detection.checkTimeTransitions();

        assertEquals(EvaluationStatus.ANOMALY_DETECTED, result.status());
        assertEquals("ConveyorBeltSwapDepartureLimit", result.transitionName());
    }

    @Test
    void aTokenThatLeavesTheFeedButNeverReachesTheSwapViolatesTheFeedDepartureLimit() {
        tokenDroppedAtTheBeltFeed();
        clock.addAndGet(3_000);
        observe("cb.conveyorSensFeed", true); // the belt starts, then stops on the way
        assertEquals("TokenDroppedOffAtConveyorBeltFeed", detection.getCurrentState());

        clock.addAndGet(21_000);
        assertEquals(EvaluationStatus.WAITING_FOR_MATCH, detection.checkTimeTransitions().status());

        clock.addAndGet(2_000); // past the 25 s of ConveyorBeltFeedDepartureLimit, from the drop on the feed
        AnomalyEvaluationResult result = detection.checkTimeTransitions();

        assertEquals(EvaluationStatus.ANOMALY_DETECTED, result.status());
        assertEquals("ConveyorBeltFeedDepartureLimit", result.transitionName());
    }

    /** Up to the token waiting in the blue chute (Token_t0, Token_t1Blue), with VGR1 holding the vacuum. */
    private void tokenInTheBlueChuteAndVgr1Gripping() {
        observe("sl.sensor_SL_in", false);
        observe("sl.sensor_SL_in", true);
        values.put("sl.sensor_SL_white", true);
        values.put("sl.sensor_SL_red", true);
        observe("sl.sensor_SL_blue", false);
        assertEquals("TokenAtSortingLineBlueOutput", detection.getCurrentState());
        values.put("vgr1.vacuumActCompressorOn", true);
        values.put("vgr1.vacuumActValve", true);
    }

    @Test
    void theTokenIsPickedUpWhenVgr1IsAtItsBlueChuteNamedPosition() {
        tokenInTheBlueChuteAndVgr1Gripping();
        values.put("vgr1.rotEncoder", 2190); // SL_OUTPUT_BLUE: rot 2175, arm 1500, within 50 counts
        values.put("vgr1.armEncoder", 1480);

        observe("sl.sensor_SL_blue", true); // the token leaves the chute

        assertEquals("TokenPickedUpByVGR1", detection.getCurrentState());
    }

    @Test
    void theTokenIsNotPickedUpByVgr1AtAnotherPosition() {
        tokenInTheBlueChuteAndVgr1Gripping();
        values.put("vgr1.rotEncoder", 2400); // over the white chute
        values.put("vgr1.armEncoder", 500);

        observe("sl.sensor_SL_blue", true);

        assertEquals("TokenAtSortingLineBlueOutput", detection.getCurrentState());
    }

    @Test
    void theGuardDescriptionGivesTheValueAndResultOfEachClause() {
        tokenInTheBlueChuteAndVgr1Gripping();
        values.put("vgr1.rotEncoder", 2400);
        values.put("sl.sensor_SL_blue", true);

        List<GuardTransitionEvaluation> guards = detection.describeGuards(detection.getCurrentState(), values);

        assertEquals(1, guards.size());
        GuardTransitionEvaluation blue = guards.get(0);
        assertEquals("Token_t2Blue", blue.transitionName());
        assertTrue(blue.anyClauseTrue());
        assertEquals(List.of(
                new GuardClauseEvaluation("sl.sensor_SL_blue == true",
                        List.of(new FoundValue("sl.sensor_SL_blue", true, true)), Result.TRUE),
                new GuardClauseEvaluation("vgr1.vacuumActCompressorOn == true",
                        List.of(new FoundValue("vgr1.vacuumActCompressorOn", true, true)), Result.TRUE),
                new GuardClauseEvaluation("vgr1.vacuumActValve == true",
                        List.of(new FoundValue("vgr1.vacuumActValve", true, true)), Result.TRUE),
                new GuardClauseEvaluation("IsInTolerance(vgr1.rotEncoder, 2175, 100)",
                        List.of(new FoundValue("vgr1.rotEncoder", 2400, true)), Result.FALSE),
                new GuardClauseEvaluation("IsInTolerance(vgr1.armEncoder, 1500, 100)",
                        List.of(new FoundValue("vgr1.armEncoder", null, false)), Result.UNOBSERVED)),
                blue.clauses());
    }

    @Test
    void aTrueClauseIsPrintedInBoldGreenAndTheOthersInBoldRed() {
        String text = AnomalyDetectionImpl.formatGuards(List.of(new GuardTransitionEvaluation("Token_t2Blue", List.of(
                new GuardClauseEvaluation("sl.sensor_SL_blue == true",
                        List.of(new FoundValue("sl.sensor_SL_blue", true, true)), Result.TRUE),
                new GuardClauseEvaluation("IsInTolerance(vgr1.armEncoder, 1500, 100)",
                        List.of(new FoundValue("vgr1.armEncoder", null, false)), Result.UNOBSERVED)))));

        assertTrue(text.contains("Token_t2Blue:"), text);
        assertTrue(text.contains("\u001B[1;32mexpected: [sl.sensor_SL_blue == true], found: [sl.sensor_SL_blue=true]"
                + "\u001B[0m"), text);
        assertTrue(text.contains("\u001B[1;31mexpected: [IsInTolerance(vgr1.armEncoder, 1500, 100)], "
                + "found: [vgr1.armEncoder=<unobserved>]\u001B[0m"), text);
    }

}
