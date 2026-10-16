package mbdo.dt.services.impl;

import mbdo.dt.models.BehavioralElementExtractor;
import mbdo.dt.models.ConstraintExtractor;
import mbdo.dt.models.DatabaseSchemaDeriver;
import mbdo.dt.models.ReplanningElementExtractor;
import mbdo.dt.models.RuntimeInterpreter;
import mbdo.dt.models.StateMachineSelector;
import mbdo.dt.models.StructuralDefinitionExtractor;
import mbdo.dt.models.SysMLV2Parser;
import mbdo.dt.services.AnomalyDetectionService.AnomalyEvaluationResult;
import mbdo.dt.services.AnomalyDetectionService.EvaluationStatus;
import mbdo.dt.services.AnomalyDetectionService.TokenRunState;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several tokens on the line, each followed by its own run of the TokenLocation model from src/main/resources. */
@SpringJUnitConfig(classes = {SysMLV2Parser.class, StructuralDefinitionExtractor.class, BehavioralElementExtractor.class,
        ConstraintExtractor.class, ReplanningElementExtractor.class, DatabaseSchemaDeriver.class,
        StateMachineSelector.class, RuntimeInterpreter.class})
class AnomalyDetectionMultiTokenTest {

    @Autowired
    private SysMLV2Parser parser;
    @Autowired
    private StateMachineSelector selector;
    @Autowired
    private RuntimeInterpreter interpreter;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Map<String, Object> values = new HashMap<>();
    private AnomalyDetectionImpl detection;

    @BeforeEach
    void setUp() throws IOException {
        List<SysMLV2Parser.ParsedSysMLModel> models = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:models/**/*.sysml")) {
            models.add(parser.loadModel(resource));
        }
        detection = new AnomalyDetectionImpl(clock::get, interpreter, selector, "TokenLocation");
        detection.activateModels(models);

        // light barriers are active-low: nothing in front of them
        values.put("sl.sensor_SL_in", true);
        values.put("sl.sensor_SL_white", true);
        values.put("sl.sensor_SL_red", true);
        values.put("sl.sensor_SL_blue", true);
        values.put("cb.conveyorSensFeed", true);
        values.put("cb.conveyorSensSwap", true);
        values.put("vgr1.vacuumActCompressorOn", false);
        values.put("vgr1.vacuumActValve", false);
        values.put("vgr2.vacuumActCompressorOn", false);
        values.put("vgr2.vacuumActValve", false);
        values.put("mpo.sensor_MPS_in", true);
        values.put("mpo.sensor_MPS_out", true);
    }

    private void observe(String attribute, Object value) {
        values.put(attribute, value);
        values.put("timestamp", clock.get());
        detection.evaluateObservation(new HashMap<>(values));
    }

    private List<String> tokenStates() {
        return detection.getTokens().stream().map(TokenRunState::state).toList();
    }

    /** A token arrives at the SortingLine input, then goes on the belt towards the color outputs. */
    private void tokenEntersTheSortingLine() {
        observe("sl.sensor_SL_in", false);
        observe("sl.sensor_SL_in", true);
    }

    /** The token on the SortingLine reaches the blue chute. */
    private void tokenReachesTheBlueChute() {
        observe("sl.sensor_SL_blue", false);
    }

    /** VGR1 picks the token in the blue chute (at its SL_OUTPUT_BLUE named position) and drops it on the CB feed. */
    private void vgr1BringsTheBlueTokenToTheFeed() {
        values.put("vgr1.rotEncoder", 2175);
        values.put("vgr1.armEncoder", 1500);
        values.put("vgr1.vacuumActCompressorOn", true);
        values.put("vgr1.vacuumActValve", true);
        observe("sl.sensor_SL_blue", true);
        values.put("vgr1.vacuumActCompressorOn", false);
        values.put("vgr1.vacuumActValve", false);
        observe("cb.conveyorSensFeed", false);
    }

    @Test
    void eachTokenAtTheInputGetsItsOwnRun() {
        tokenEntersTheSortingLine();
        tokenReachesTheBlueChute();
        observe("sl.sensor_SL_in", false); // a second token at the input

        assertEquals(List.of("TokenAtSortingLineBlueOutput", "TokenAtSortingLineInput"), tokenStates());
        assertEquals(List.of(1, 2), detection.getTokens().stream().map(TokenRunState::token).toList());
        assertEquals("TokenAtSortingLineInput", detection.getCurrentState(), "the most recent token");
    }

    @Test
    void aTokenStandingAtTheInputIsCountedOnce() {
        observe("sl.sensor_SL_in", false);
        observe("vgr1.rotEncoder", 100); // other observations while the token is still at the input
        observe("vgr1.rotEncoder", 200);

        assertEquals(List.of("TokenAtSortingLineInput"), tokenStates());
    }

    @Test
    void theNextTokenIsFollowedOnceTheInputWasFree() {
        tokenEntersTheSortingLine();
        tokenReachesTheBlueChute();
        vgr1BringsTheBlueTokenToTheFeed();
        tokenEntersTheSortingLine(); // the second token

        assertEquals(List.of("TokenDroppedOffAtConveyorBeltFeed", "TokenAtSortingLineInput"), tokenStates());
    }

    @Test
    void aTokenThatLeftTheLineIsNoLongerFollowed() {
        tokenEntersTheSortingLine();
        tokenReachesTheBlueChute();
        vgr1BringsTheBlueTokenToTheFeed();
        observe("sl.sensor_SL_in", false); // the second token arrives meanwhile

        // the conveyor belt brings the first token to the swap, VGR2 (at ALT_CB) takes it to the MPS
        values.put("cb.conveyorSensFeed", true);
        observe("cb.conveyorSensSwap", false);
        values.put("vgr2.rotEncoder", 2650);
        values.put("vgr2.armEncoder", 1350);
        values.put("vgr2.vacuumActCompressorOn", true);
        values.put("vgr2.vacuumActValve", true);
        observe("cb.conveyorSensSwap", true);
        values.put("vgr2.vacuumActCompressorOn", false);
        values.put("vgr2.vacuumActValve", false);
        observe("mpo.sensor_MPS_in", false);
        values.put("mpo.sensor_MPS_out", false);
        observe("mpo.sensor_MPS_in", true); // processed: at the MPS output, it leaves the line

        assertEquals(List.of("TokenAtSortingLineInput"), tokenStates());
        assertEquals(2, detection.getTokens().get(0).token());
    }

    @Test
    void anAnomalyOnOneTokenDoesNotStopTheOthers() {
        tokenEntersTheSortingLine();
        tokenReachesTheBlueChute();
        vgr1BringsTheBlueTokenToTheFeed();
        tokenEntersTheSortingLine(); // the second token

        clock.addAndGet(26_000); // the first token stays on the feed: the belt failed
        AnomalyEvaluationResult result = detection.checkTimeTransitions();

        assertEquals(EvaluationStatus.ANOMALY_DETECTED, result.status());
        assertEquals("ConveyorBeltFeedDepartureLimit", result.transitionName());
        assertTrue(result.message().startsWith("Token #1: "), result.message());
        assertEquals(List.of(result), detection.detectDisruptions(), "the anomaly of the first token only");
        assertTrue(detection.getTokens().get(0).anomaly());
        assertFalse(detection.getTokens().get(1).anomaly());

        tokenReachesTheBlueChute(); // the second token is still followed
        assertEquals("TokenAtSortingLineBlueOutput", tokenStates().get(1));

        tokenEntersTheSortingLine(); // and new tokens still enter the line
        assertEquals(3, detection.getTokens().size());
    }

    @Test
    void validatingTheAnomalyEndsTheRunOfItsTokenOnly() {
        tokenEntersTheSortingLine();
        tokenReachesTheBlueChute();
        vgr1BringsTheBlueTokenToTheFeed();
        tokenEntersTheSortingLine(); // the second token
        clock.addAndGet(26_000);
        String anomalyId = detection.checkTimeTransitions().anomalyContext().anomalyId();
        assertEquals(anomalyId, detection.getTokens().get(0).anomalyId());
        assertNull(detection.getTokens().get(1).anomalyId());

        assertTrue(detection.releaseAnomaly(anomalyId));

        assertEquals(List.of(2), detection.getTokens().stream().map(TokenRunState::token).toList());
        assertFalse(detection.isAnomalyDetected());
        assertFalse(detection.releaseAnomaly(anomalyId), "already dealt with");

        tokenReachesTheBlueChute(); // the second token is still followed
        assertEquals(List.of("TokenAtSortingLineBlueOutput"), tokenStates());
    }

    @Test
    void anUnknownAnomalyIsNotReleased() {
        tokenEntersTheSortingLine();

        assertFalse(detection.releaseAnomaly("no-such-anomaly"));
        assertEquals(1, detection.getTokens().size());
    }
}
