package mbdo.dt.replanning;

import mbdo.dt.replanning.scada.ScadaMachine;
import mbdo.dt.replanning.scada.ScadaMachine.ScadaMission;

import java.util.List;

/** The replanning model of src/main/resources, and SCADA machines as the SCADA of the REN missions reports them. */
public final class ReplanningTestModels {

    public static final String CB = "REN_MISSION_01/CB01";
    public static final String VGR1 = "REN_MISSION_01/VGR01";
    public static final String VGR2 = "REN_MISSION_01/VGR02";
    public static final String ZONE_CB = "ZoneCB";

    public static final List<String> VGR1_NOMINAL_STATES = List.of("SetupCMD", "StandbyCMD", "AskSortingLine", "Idle", "PickColorCMD", "IdlePicked", "StandbyWithTokenCMD", "AskCBSlotInStandby", "SelectCBSlotInStandby", "WaitForFreeCBSlot", "PlaceConveyorBeltFeed", "RetractFromCBCMD", "CBZoneReleased", "ReturnToStandbyCMD");
    public static final List<String> VGR1_CB_FAILURE_STATES = List.of("SetupCMD", "StandbyCMD", "AskSortingLine", "Idle", "PickColorCMD", "AskCBSlot", "SelectCBSlot", "StandbyWithTokenCMD", "AskCBSlotInStandby", "SelectCBSlotInStandby", "WaitForFreeCBSlot", "PlaceConveyorBeltCMD", "RetractFromCBCMD", "CBZoneReleased", "ReturnToStandbyCMD");
    public static final List<String> VGR2_NOMINAL_STATES = List.of("SetupCMD", "StandbyCMD", "CheckSwap", "Idle", "WaitForCBZoneAcquisition", "PickCBswapCMD", "RetractFromCBCMD", "CBZoneReleased", "StandbyBeforeMPSCMD", "WaitForMPSZoneAcquisition", "PlaceMPSinCMD", "RetractFromMPSCMD", "MPSZoneReleased", "ReturnToStandbyCMD");
    public static final List<String> VGR2_CB_FAILURE_STATES = List.of("SetupCMD", "StandbyCMD", "CheckCB", "SelectCBSlot",
            "Idle", "PickCBCMD", "RetractFromCBCMD", "CBZoneReleased", "WaitForMPSZoneAcquisition", "PlaceMPSinCMD",
            "RetractFromMPSCMD", "MPSZoneReleased", "ReturnToStandbyCMD");
    public static final List<String> ZONE_CB_NOMINAL_STATES = List.of("CheckBelt", "Empty", "TokenOnFeed", "TokenOnSwap", "SwapThenFeed", "VGR1PlacingOnFeed", "VGR1PlacingOnFeedTokenMovedToSwap", "VGR1PlacingOnFeedAfterSwap", "VGR2PickingLastToken", "VGR2PickingSwapBeforeFeed", "VGR2PickingSwapFeedMovedToSwap");
    public static final List<String> ZONE_DEAD_CB_STATES = List.of("Empty", "TokenOnFeed", "TokenOnSwap", "FeedThenSwap",
            "SwapThenFeed", "VGR1PlacingOnSwap", "VGR1PlacingOnSwapAfterFeed", "VGR1PlacingOnFeedAfterSwap",
            "VGR2PickingLastToken", "VGR2PickingFeedBeforeSwap", "VGR2PickingSwapBeforeFeed");

    private ReplanningTestModels() {
    }

    public static ReplanningModel load() {
        return ReplanningModel.load("classpath*:models/**/*.sysml");
    }

    public static ScadaMachine cb(String mission, String state) {
        return new ScadaMachine(CB, "ConveyorBelt01", "ConveyorBeltMachine", true, mission, state, List.of(
                new ScadaMission("ConveyorBeltNominalMission", "", List.of("Idle", "MovingToSensor"),
                        mission != null, state)));
    }

    public static ScadaMachine vgr1(String mission, String state) {
        return new ScadaMachine(VGR1, "VacuumGripper01", "VacuumGripperMachine", true, mission, state, List.of(
                new ScadaMission("VacuumGripper1NominalMission", "", VGR1_NOMINAL_STATES, false, null),
                new ScadaMission("VacuumGripper1CBFailureMission", "", VGR1_CB_FAILURE_STATES, false, null)));
    }

    public static ScadaMachine vgr2(String mission, String state) {
        return new ScadaMachine(VGR2, "VacuumGripper02", "VacuumGripperMachine", true, mission, state, List.of(
                new ScadaMission("VacuumGripper2NominalMission", "", VGR2_NOMINAL_STATES, false, null),
                new ScadaMission("VacuumGripper2CBFailureMission", "", VGR2_CB_FAILURE_STATES, false, null)));
    }

    public static ScadaMachine zoneCB(String mission, String state) {
        return new ScadaMachine(ZONE_CB, null, "Zone", null, mission, state, List.of(
                new ScadaMission("ZoneMissionCBNominal", "", ZONE_CB_NOMINAL_STATES, false, null),
                new ScadaMission("ZoneMissionDeadCB", "", ZONE_DEAD_CB_STATES, false, null)));
    }
}
