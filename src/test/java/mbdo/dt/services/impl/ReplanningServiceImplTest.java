package mbdo.dt.services.impl;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentJournal;
import mbdo.dt.incident.ReplanningIncidentRegistryService;
import mbdo.dt.incident.ReplanningIncidentResponse;
import mbdo.dt.incident.ReplanningIncidentSseService;
import mbdo.dt.replanning.ReplanningModel;
import mbdo.dt.replanning.ReplanningPlan;
import mbdo.dt.replanning.ReplanningPlan.Kind;
import mbdo.dt.replanning.ReplanningPlan.Status;
import mbdo.dt.replanning.ReplanningPlan.Step;
import mbdo.dt.replanning.ReplanningPlan.StepStatus;
import mbdo.dt.replanning.scada.ScadaClient;
import mbdo.dt.replanning.scada.ScadaCommandResult;
import mbdo.dt.replanning.scada.ScadaConflictException;
import mbdo.dt.replanning.scada.ScadaMachine;
import mbdo.dt.replanning.ReplanningTestModels;
import mbdo.dt.replanning.scada.ScadaMissionChange;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;
import mbdo.dt.services.AnomalyDetection.EvaluationStatus;
import mbdo.dt.services.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static mbdo.dt.replanning.ReplanningTestModels.CB;
import static mbdo.dt.replanning.ReplanningTestModels.VGR1;
import static mbdo.dt.replanning.ReplanningTestModels.VGR2;
import static mbdo.dt.replanning.ReplanningTestModels.ZONE_CB;
import static mbdo.dt.replanning.ReplanningTestModels.cb;
import static mbdo.dt.replanning.ReplanningTestModels.vgr1;
import static mbdo.dt.replanning.ReplanningTestModels.vgr2;
import static mbdo.dt.replanning.ReplanningTestModels.zoneCB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The conveyor belt failure replanning: planned on the states the SCADA reports, executed on a fake SCADA. */
class ReplanningServiceImplTest {

    private static final String ANOMALY = "anomaly-1";

    /** Records the requests; a machine's mission and state are what the test sets, or what the requests made. */
    static final class FakeScada implements ScadaClient {
        final List<String> requests = new ArrayList<>();
        final Map<String, ScadaMachine> machines = new LinkedHashMap<>();
        final Map<String, String> commandStatuses = new HashMap<>();
        /** a machine that moves on (to this state) when the replanning first tries to swap it */
        String movesOnMachine;
        String movesOnTo;

        @Override
        public synchronized List<ScadaMachine> machines() {
            return List.copyOf(machines.values());
        }

        @Override
        public synchronized ScadaMissionChange swapMission(String machine, String mission, String state,
                                                           String expectedMission, String expectedState) {
            ScadaMachine current = machines.get(machine);
            if (machine.equals(movesOnMachine)) {
                movesOnMachine = null;
                machines.put(machine, with(current, current.activeMission(), movesOnTo));
                throw new ScadaConflictException(machine + " moved on");
            }
            check(current, expectedMission, expectedState);
            requests.add("swap " + machine + " " + mission + "::" + state);
            machines.put(machine, with(current, mission, state));
            return new ScadaMissionChange(machine, current.activeMission(), current.activeState(), mission, state);
        }

        @Override
        public synchronized ScadaMissionChange stopMission(String machine, String expectedMission,
                                                           String expectedState) {
            ScadaMachine current = machines.get(machine);
            check(current, expectedMission, expectedState);
            requests.add("stop " + machine);
            machines.put(machine, with(current, null, null));
            return new ScadaMissionChange(machine, current.activeMission(), current.activeState(), null, null);
        }

        @Override
        public synchronized ScadaCommandResult executeCommand(String machine, String command,
                                                              List<Map<String, Object>> parameters, long timeoutMs) {
            requests.add("command " + machine + " " + command);
            String status = commandStatuses.getOrDefault(command, "DONE");
            return new ScadaCommandResult(machine, machine, command, "1", status, status.equals("DONE"), 10);
        }

        private static void check(ScadaMachine machine, String expectedMission, String expectedState) {
            String running = machine.activeMission() == null ? "" : machine.activeMission();
            if ((expectedMission != null && !expectedMission.equals(running))
                    || (expectedState != null && !expectedState.equals(machine.activeState()))) {
                throw new ScadaConflictException(machine.name() + " is in " + machine.activeState());
            }
        }

        private static ScadaMachine with(ScadaMachine machine, String mission, String state) {
            return new ScadaMachine(machine.name(), machine.physicalMachine(), machine.type(), machine.idle(),
                    mission, state, machine.availableMissions());
        }
    }

    private final ReplanningModel model = ReplanningTestModels.load();
    private final ReplanningModel.Strategy cbFailure = model.findStrategy("cbFailure").orElseThrow();
    private final Map<String, Object> values = new HashMap<>();
    private final List<ReplanningPlan> published = new ArrayList<>();
    private final ReplanningIncidentJournal journal = mock(ReplanningIncidentJournal.class);
    private FakeScada scada;
    private ReplanningServiceImpl service;

    @BeforeEach
    void setUp() {
        scada = new FakeScada();
        scada.machines.put(CB, cb("ConveyorBeltNominalMission", "MovingToSensor"));
        scada.machines.put(ZONE_CB, zoneCB("ZoneMissionCBNominal", "TokenOnFeed"));
        scada.machines.put(VGR1, vgr1("VacuumGripper1NominalMission", "Idle"));
        scada.machines.put(VGR2, vgr2("VacuumGripper2NominalMission", "Idle"));
        tokenStuckOnTheFeed();

        ReplanningIncidentRegistryService incidents = mock(ReplanningIncidentRegistryService.class);
        when(incidents.attachPlan(eq(ANOMALY), any())).thenAnswer(call -> {
            synchronized (published) {
                published.add(call.getArgument(1));
            }
            return Optional.of(mock(ReplanningIncidentResponse.class));
        });
        RuntimeContext runtimeContext = mock(RuntimeContext.class);
        when(runtimeContext.snapshot()).thenAnswer(call -> new HashMap<>(values));
        service = new ReplanningServiceImpl(scada, incidents, mock(ReplanningIncidentSseService.class), journal,
                runtimeContext,
                "classpath*:models/**/*.sysml", 20, 2000, 1000);
        service.loadModel();
    }

    // ---- planning ----

    @Test
    void theBeltIsStoppedThenTheDeadCBZoneAndTheGrippersStartFromTheirStates() {
        ReplanningPlan plan = plan();

        assertEquals(Status.PLANNED, plan.status(), plan.problems().toString());
        assertEquals(List.of(Kind.STOP_MISSION, Kind.MIGRATE, Kind.MIGRATE, Kind.MIGRATE),
                plan.steps().stream().map(Step::kind).toList());
        assertEquals("TokenOnFeed", plan.steps().get(1).toState());
        assertEquals("AskSortingLine", plan.steps().get(2).toState(), "it asks the sorting line at once");
        assertEquals("CheckCB", plan.steps().get(3).toState(), "it asks the zone at once: the stuck token");
    }

    @Test
    void eachLightBarrierCombinationGivesTheDeadCBZoneStateWhateverTheNominalZoneState() {
        Map<List<Boolean>, String> expected = Map.of(
                List.of(true, true), "Empty",
                List.of(false, true), "TokenOnFeed",
                List.of(true, false), "TokenOnSwap",
                List.of(false, false), "SwapThenFeed");
        for (String state : List.of("Empty", "TokenOnFeed", "TokenOnSwap", "SwapThenFeed")) {
            scada.machines.put(ZONE_CB, zoneCB("ZoneMissionCBNominal", state));
            expected.forEach((feedAndSwap, deadCBState) -> {
                values.put("cb.conveyorSensFeed", feedAndSwap.get(0));
                values.put("cb.conveyorSensSwap", feedAndSwap.get(1));

                assertEquals(deadCBState, plan().steps().get(1).toState(), state + " " + feedAndSwap);
            });
        }
    }

    @Test
    void withNothingObservedTheDeadCBZoneStartsEmpty() {
        values.clear();

        assertEquals("Empty", plan().steps().get(1).toState());
    }

    @Test
    void vgr1HoldingATokenAsksTheDeadCBZoneWhereToPlaceIt() {
        scada.machines.put(VGR1, vgr1("VacuumGripper1NominalMission", "IdlePicked"));

        assertEquals("AskCBSlot", plan().steps().get(2).toState());
    }

    @Test
    void aStateWithoutRuleKeepsItsNameInTheAlternativeMission() {
        scada.machines.put(VGR1, vgr1("VacuumGripper1NominalMission", "PickColorCMD"));
        scada.machines.put(VGR2, vgr2("VacuumGripper2NominalMission", "MPSZoneReleased"));

        ReplanningPlan plan = plan();

        assertEquals("PickColorCMD", plan.steps().get(2).toState(), "the pick success reaches the new mission");
        assertEquals("same state", plan.steps().get(2).rule());
        assertEquals("MPSZoneReleased", plan.steps().get(3).toState());
    }

    @Test
    void aGripperOverTheBeltMakesTheZoneWaitAndVgr1WaitingInStandbyAsksTheDeadZoneAtOnce() {
        scada.machines.put(ZONE_CB, zoneCB("ZoneMissionCBNominal", "VGR2PickingLastToken"));
        scada.machines.put(VGR1, vgr1("VacuumGripper1NominalMission", "WaitForFreeCBSlot"));
        scada.machines.put(VGR2, vgr2("VacuumGripper2NominalMission", "PickCBswapCMD"));

        ReplanningPlan plan = plan();

        assertEquals(Status.WAITING, plan.status());
        assertEquals(2, plan.problems().size(), plan.problems().toString());
        assertTrue(plan.problems().get(0).startsWith("ZoneCB is in VGR2PickingLastToken"), plan.problems().toString());

        scada.machines.put(ZONE_CB, zoneCB("ZoneMissionCBNominal", "TokenOnFeed"));
        scada.machines.put(VGR2, vgr2("VacuumGripper2NominalMission", "WaitForCBZoneAcquisition"));
        plan = plan();
        assertEquals(Status.PLANNED, plan.status(), plan.problems().toString());
        assertEquals("AskCBSlotInStandby", plan.steps().get(2).toState());
        assertEquals("CheckCB", plan.steps().get(3).toState());
    }

    @Test
    void vgr2OnItsWayToTheMpsGoesOnWithoutTheSafePosition() {
        scada.machines.put(VGR2, vgr2("VacuumGripper2NominalMission", "StandbyBeforeMPSCMD"));

        assertEquals("CBZoneReleased", plan().steps().get(3).toState(), "requests the MPS zone at once");
    }

    @Test
    void aGripperOverTheBeltMakesThePlanWait() {
        scada.machines.put(VGR1, vgr1("VacuumGripper1NominalMission", "PlaceConveyorBeltFeed"));
        scada.machines.put(VGR2, vgr2("VacuumGripper2NominalMission", "RetractFromCBCMD"));

        ReplanningPlan plan = plan();

        assertEquals(Status.WAITING, plan.status());
        assertTrue(plan.problems().get(0).contains("is in PlaceConveyorBeltFeed: waiting"), plan.problems().toString());
        assertTrue(plan.problems().get(1).contains("waiting for it to leave"), plan.problems().toString());
    }

    @Test
    void aMachineRunningAnotherMissionMakesThePlanWait() {
        scada.machines.put(VGR1, vgr1(null, null));

        ReplanningPlan plan = plan();

        assertEquals(Status.WAITING, plan.status());
        assertTrue(plan.problems().get(0).contains("runs no mission"), plan.problems().toString());
    }

    @Test
    void theBeltIsStoppedEvenWithoutMissionAndAMigratedMachineIsSkipped() {
        scada.machines.put(CB, cb(null, null));
        scada.machines.put(ZONE_CB, zoneCB("ZoneMissionDeadCB", "Empty"));
        scada.machines.put(VGR1, vgr1("VacuumGripper1CBFailureMission", "Idle"));

        ReplanningPlan plan = plan();

        assertEquals(Status.PLANNED, plan.status(), plan.problems().toString());
        assertEquals(List.of(StepStatus.PENDING, StepStatus.SKIPPED, StepStatus.SKIPPED, StepStatus.PENDING),
                plan.steps().stream().map(Step::status).toList(), "the belt's command may still run");
    }

    // ---- execution ----

    @Test
    void stopsTheBeltCompletelyThenMigratesTheCBZoneAndBothGrippersWithoutApproval() {
        ReplanningPlan executed = replanned();

        assertEquals(Status.EXECUTED, executed.status(), executed.message());
        verify(journal, timeout(2000)).recordReplanning(ANOMALY, ReplanningIncidentJournal.REPLANNING, executed);
        assertEquals(List.of(
                "stop " + CB,
                "command " + CB + " stop",
                "swap " + ZONE_CB + " ZoneMissionDeadCB::TokenOnFeed",
                "swap " + VGR1 + " VacuumGripper1CBFailureMission::AskSortingLine",
                "swap " + VGR2 + " VacuumGripper2CBFailureMission::CheckCB"), scada.requests);
    }

    @Test
    void aBeltThatDoesNotStartTriggersTheSameReplanning() {
        ReplanningPlan executed = replanned("ConveyorBeltFeedDepartureLimit");

        assertEquals(Status.EXECUTED, executed.status(), executed.message());
        assertEquals("cbFailure", executed.strategy());
    }

    @Test
    void aPlanThatWaitsIsPlannedAgainUntilItCanBeExecuted() {
        scada.machines.put(VGR1, vgr1("VacuumGripper1NominalMission", "PlaceConveyorBeltFeed"));
        new Thread(() -> {
            sleep(100);
            synchronized (scada) {
                scada.machines.put(VGR1, vgr1("VacuumGripper1NominalMission", "ReturnToStandbyCMD"));
            }
        }).start();

        ReplanningPlan executed = replanned();

        assertEquals(Status.EXECUTED, executed.status(), executed.message());
        assertTrue(published.stream().anyMatch(p -> p.status() == Status.WAITING));
        assertTrue(scada.requests.contains("swap " + VGR1 + " VacuumGripper1CBFailureMission::ReturnToStandbyCMD"));
    }

    @Test
    void aMachineThatMovedOnIsPlannedAgainFromItsNewState() {
        scada.movesOnMachine = VGR1;
        scada.movesOnTo = "PickColorCMD"; // a token arrived at the sorting line meanwhile

        ReplanningPlan executed = replanned();

        assertEquals(Status.EXECUTED, executed.status(), executed.message());
        assertTrue(scada.requests.contains("swap " + VGR1 + " VacuumGripper1CBFailureMission::PickColorCMD"),
                scada.requests.toString());
    }

    @Test
    void aBeltThatDoesNotStopStopsTheReplanning() {
        scada.commandStatuses.put("stop", "TIMEOUT");

        ReplanningPlan executed = replanned();

        assertEquals(Status.FAILED, executed.status());
        assertTrue(executed.steps().get(0).detail().contains("TIMEOUT"), executed.steps().get(0).detail());
        assertTrue(scada.requests.stream().noneMatch(r -> r.startsWith("swap ")), "no gripper works over the belt");
    }

    // ---- recovery ----

    @Test
    void onceTheOperatorRepairedTheBeltTheMachinesGoBackToTheirNominalMissionsAndTheBeltRestarts() {
        assertEquals(Status.EXECUTED, replanned().status());
        synchronized (scada) {
            scada.machines.put(ZONE_CB, zoneCB("ZoneMissionDeadCB", "TokenOnSwap"));
            scada.machines.put(VGR1, vgr1("VacuumGripper1CBFailureMission", "Idle"));
            scada.machines.put(VGR2, vgr2("VacuumGripper2CBFailureMission", "Idle"));
            scada.requests.clear();
        }

        service.recover(ANOMALY);

        List<String> expected = List.of(
                "swap " + ZONE_CB + " ZoneMissionCBNominal::CheckBelt",
                "swap " + VGR1 + " VacuumGripper1NominalMission::Idle",
                "swap " + VGR2 + " VacuumGripper2NominalMission::CheckSwap",
                "swap " + CB + " ConveyorBeltNominalMission::null");
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && scada.requests.size() < expected.size()) {
            sleep(10);
        }
        assertEquals(expected, scada.requests);
        verify(journal, timeout(2000)).recordReplanning(eq(ANOMALY), eq(ReplanningIncidentJournal.RECOVERY),
                argThat(plan -> plan.status() == Status.EXECUTED));
    }

    @Test
    void theRecoveryWaitsForTheGrippersToLeaveTheBeltButNotForTheTokensOnIt() {
        ReplanningModel.Strategy cbRepaired = model.findStrategy(cbFailure.recoveryStrategy()).orElseThrow();
        scada.machines.put(CB, cb(null, null));
        scada.machines.put(ZONE_CB, zoneCB("ZoneMissionDeadCB", "VGR1PlacingOnSwapAfterFeed"));
        scada.machines.put(VGR1, vgr1("VacuumGripper1CBFailureMission", "PlaceConveyorBeltCMD"));
        scada.machines.put(VGR2, vgr2("VacuumGripper2CBFailureMission", "Idle"));

        ReplanningPlan plan = ReplanningServiceImpl.plan(cbRepaired, model, scada.machines(), values, null);

        assertEquals(Status.WAITING, plan.status());
        assertEquals(2, plan.problems().size(), plan.problems().toString());

        // VGR1 placed and went back: tokens left on the feed and the swap, the nominal zone reads them (CheckBelt)
        scada.machines.put(ZONE_CB, zoneCB("ZoneMissionDeadCB", "SwapThenFeed"));
        scada.machines.put(VGR1, vgr1("VacuumGripper1CBFailureMission", "WaitForFreeCBSlot"));
        plan = ReplanningServiceImpl.plan(cbRepaired, model, scada.machines(), values, null);
        assertEquals(Status.PLANNED, plan.status(), plan.problems().toString());
        assertEquals("CheckBelt", plan.steps().get(0).toState());
        assertEquals("AskCBSlotInStandby", plan.steps().get(1).toState());
        assertEquals("CheckSwap", plan.steps().get(2).toState());
    }

    @Test
    void aRepairedAnomalyWithoutReplanningDoesNothing() {
        service.recover("unknown");

        sleep(100);
        assertTrue(scada.requests.isEmpty(), scada.requests.toString());
    }

    private ReplanningPlan plan() {
        return ReplanningServiceImpl.plan(cbFailure, model, scada.machines(), values, null);
    }

    /** Raises the swap arrival anomaly, and waits for its replanning to end. */
    private ReplanningPlan replanned() {
        return replanned("ConveyorBeltSwapArrivalLimit");
    }

    /** Raises the anomaly of the violated {@code constraint}, and waits for its replanning to end. */
    private ReplanningPlan replanned(String constraint) {
        AnomalyContext context = new AnomalyContext(ANOMALY, Instant.now(), "TokenLocation",
                "TokenDroppedOffAtConveyorBeltFeed", "cb", "TokenDroppedOffAtConveyorBeltFeed",
                "TokenDroppedOffAtConveyorBeltFeed", "Token #1: feed departure limit");
        service.handleAnomaly(null, new AnomalyEvaluationResult(EvaluationStatus.ANOMALY_DETECTED, "TokenLocation",
                "TokenLocation", "TokenDroppedOffAtConveyorBeltFeed", "TokenDroppedOffAtConveyorBeltFeed",
                constraint, constraint, context), new HashMap<>(values));
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (published) {
                if (!published.isEmpty()) {
                    ReplanningPlan last = published.get(published.size() - 1);
                    if (last.status() == Status.EXECUTED || last.status() == Status.FAILED) {
                        return last;
                    }
                }
            }
            sleep(10);
        }
        throw new AssertionError("The replanning didn't end: " + published);
    }

    private void tokenStuckOnTheFeed() {
        values.put("cb.conveyorSensFeed", false); // light barriers are active-low
        values.put(ReplanningServiceImpl.TOKEN_LOCATION_STATE, "TokenDroppedOffAtConveyorBeltFeed");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
