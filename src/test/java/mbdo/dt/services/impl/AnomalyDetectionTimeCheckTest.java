package mbdo.dt.services.impl;

import mbdo.dt.models.BehavioralElementExtractor;
import mbdo.dt.models.ConstraintExtractor;
import mbdo.dt.models.DatabaseSchemaDeriver;
import mbdo.dt.models.RuntimeInterpreter;
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
        values.put("encoderTolerance", 50);
        values.put("vgr1.expected3DPositionBlueOutput", 1000);
        values.put("vgr1.3DPosition", 1000);
        values.put("vgr1.vacuumActCompressorOn", true);
        values.put("vgr1.vacuumActValve", true);
        observe("sl.sensor_SL_blue", true);
        values.put("vgr2.vacuumActCompressorOn", false);
        values.put("vgr2.vacuumActValve", false);
        observe("cb.conveyorSensFeed", false);
        assertEquals("TokenDroppedOffAtConveyorBeltFeed", detection.getCurrentState());

        clock.addAndGet(5_000);
        assertEquals(EvaluationStatus.WAITING_FOR_MATCH, detection.checkTimeTransitions().status());

        clock.addAndGet(6_000);
        AnomalyEvaluationResult result = detection.checkTimeTransitions();
        assertEquals(EvaluationStatus.ANOMALY_DETECTED, result.status());
        assertEquals("ConveyorBeltFeedDepartureLimit", result.transitionName());
        assertEquals(result, reactedTo.get(reactedTo.size() - 1));
    }
}
