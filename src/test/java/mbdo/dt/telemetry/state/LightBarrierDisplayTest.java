package mbdo.dt.telemetry.state;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SL, CB and MPS light barriers are active-low on the platform; the UI state shows true = triggered. */
class LightBarrierDisplayTest {

    @Test
    void sortingLineBarrierIsTriggeredWhenThePlatformReportsFalse() {
        SortingLineState state = new SortingLineState("SLSystemModel");

        state.applyAttribute("sortingLineSensInputLightBarrier", false, Instant.now());
        state.applyAttribute("sortingLineSensRedLightBarrier", true, Instant.now());

        assertEquals(true, state.toState().get("sortingLineSensInputLightBarrier"));
        assertEquals(false, state.toState().get("sortingLineSensRedLightBarrier"));
    }

    @Test
    void conveyorBeltBarrierIsTriggeredWhenThePlatformReportsFalse() {
        ConveyorBeltState state = new ConveyorBeltState("CBSystemModel");

        state.applyAttribute("conveyorSensFeed", false, Instant.now());
        state.applyAttribute("conveyorSensSwap", true, Instant.now());

        assertEquals(true, state.toState().get("sensorFeed"));
        assertEquals(false, state.toState().get("sensorSwap"));
    }

    @Test
    void multiProcessingStationBarriersAreTriggeredWhenThePlatformReportsFalse() {
        MultiProcessingStationState state = new MultiProcessingStationState("MPSystemModel");

        state.applyAttribute("multiProcessingSensOven", false, Instant.now());
        state.applyAttribute("multiProcessingSensEndConveyor", true, Instant.now());

        assertEquals(true, state.toState().get("sensorIn"));
        assertEquals(false, state.toState().get("sensorOut"));
    }

    @Test
    void barriersAreNotTriggeredBeforeAnyMeasurement() {
        assertEquals(false, new SortingLineState("SLSystemModel").toState().get("sortingLineSensInputLightBarrier"));
        assertEquals(false, new ConveyorBeltState("CBSystemModel").toState().get("sensorFeed"));
    }
}
