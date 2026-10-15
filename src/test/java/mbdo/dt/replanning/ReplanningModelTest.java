package mbdo.dt.replanning;

import mbdo.dt.models.SysMLBehavioralModel.Operator;
import mbdo.dt.replanning.ReplanningModel.Migration;
import mbdo.dt.replanning.ReplanningModel.Rule;
import mbdo.dt.replanning.ReplanningModel.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The replanning strategies and mission migrations, as written in models/Replanning. */
class ReplanningModelTest {

    private final ReplanningModel model = ReplanningTestModels.load();

    @Test
    void theConveyorBeltFailureStrategyIsTriggeredByTheFeedDepartureLimit() {
        Strategy strategy = model.findTriggeredStrategy(
                "ConveyorBeltSwapArrivalLimit", "TokenDroppedOffAtConveyorBeltFeed").orElseThrow();

        assertEquals("cbFailure", strategy.name());
        assertEquals(List.of(ReplanningTestModels.CB), strategy.stoppedMachines());
        assertEquals(List.of("ZoneCB_NominalToDeadCB", "VGR1_NominalToCBFailure", "VGR2_NominalToCBFailure"),
                strategy.migrations(), "the zone first: it answers the grippers once they are migrated");
        assertEquals("cbRepaired", strategy.recoveryStrategy());
    }

    @Test
    void theRecoveryRestartsTheStoppedBeltLastAndIsNeverTriggeredByAnAnomaly() {
        Strategy recovery = model.findStrategy("cbRepaired").orElseThrow();

        assertEquals("CB_RestartNominal", recovery.migrations().get(recovery.migrations().size() - 1));
        assertNull(model.migrations().get("CB_RestartNominal").fromMission(), "the belt runs no mission");
        assertEquals("ZoneMissionCBNominal", model.migrations().get("ZoneCB_DeadCBToNominal").toMission());
        assertEquals("cbFailure", model.findTriggeredStrategy("ConveyorBeltSwapArrivalLimit", null)
                .orElseThrow().name());
        assertEquals("cbFailure", model.findTriggeredStrategy("ConveyorBeltFeedDepartureLimit", null)
                .orElseThrow().name(), "a belt that doesn't start, or stops on the way: the same replanning");
    }

    @Test
    void aMigrationNamesItsMachineMissionsAndWaitStates() {
        Migration migration = model.migrations().get("VGR1_NominalToCBFailure");

        assertEquals(ReplanningTestModels.VGR1, migration.machine());
        assertEquals("VacuumGripper1NominalMission", migration.fromMission());
        assertEquals("VacuumGripper1CBFailureMission", migration.toMission());
        assertEquals(List.of("PlaceConveyorBeltFeed", "RetractFromCBCMD"), migration.waitStates());
        assertEquals("AskCBSlot", migration.rulesFrom("IdlePicked").get(0).toState());
        assertEquals(List.of(), migration.rulesFrom("PickColorCMD"), "same state");
    }

    @Test
    void aRuleWithoutFirstLeavesAnyStateWithItsGuardInTheOrderOfTheModel() {
        List<Rule> rules = model.migrations().get("ZoneCB_NominalToDeadCB").rulesFrom("TokenOnSwap");

        assertEquals(List.of("zone_tokensOnSwapAndFeed", "zone_tokenOnFeed", "zone_tokenOnSwap", "zone_empty"),
                rules.stream().map(Rule::name).toList(), "the most specific guard first");
        Rule both = rules.get(0);
        assertNull(both.fromState());
        assertEquals("SwapThenFeed", both.toState());
        assertEquals(2, both.guard().size());
        assertEquals("cb.conveyorSensFeed", both.guard().get(0).leftOperand());
        assertEquals(Operator.EQUALS, both.guard().get(0).operator());
        assertEquals(List.of(), rules.get(3).guard());
    }

    @Test
    void aRuleFromAStateOfAnotherMissionIsRejected() {
        String sysml = """
                state def Wrong :> MissionMigration {
                    attribute :>> machine = "M";
                    attribute :>> fromMission = "A";
                    attribute :>> toMission = "B";
                    transition r first C::S then B::T;
                }
                """;

        assertThrows(IllegalArgumentException.class, () -> ReplanningModel.parse(sysml));
    }
}
