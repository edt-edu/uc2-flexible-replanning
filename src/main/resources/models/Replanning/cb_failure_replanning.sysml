package CBFailureReplanning {
    private import ScalarValues::*;
    private import Replanning::*;
    private import VacuumGripperSystem::*;

    doc
    /* The conveyor belt fails: the token dropped at its feed doesn't reach the swap. The belt is stopped (its mission,
       then the command it executes), and its feed and swap become two slots the grippers exchange the tokens through.
       The CB zone becomes the dead CB zone (ZoneMissionDeadCB): it knows which slots hold a token, in which order.
       VacuumGripper1CBFailureMission asks it where to place each token, VacuumGripper2CBFailureMission where to pick
       one, oldest first. The zone is migrated first, so that it answers the grippers as soon as they are migrated. */

    part cbFailure : ReplanningStrategy {
        attribute :>> description = "Conveyor belt failure: the belt is stopped, the grippers exchange the tokens on its feed and swap through the dead CB zone";
        attribute :>> triggerConstraint = ("ConveyorBeltFeedDepartureLimit", "ConveyorBeltSwapArrivalLimit");
        attribute :>> stoppedMachines = ("REN_MISSION_01/CB01");
        attribute :>> migrations = ("ZoneCB_NominalToDeadCB", "VGR1_NominalToCBFailure", "VGR2_NominalToCBFailure");
        attribute :>> recoveryStrategy = "cbRepaired";
    }

    doc
    /* The dead CB zone starts in the state the belt's light barriers (active-low) give, whatever the state of the
       nominal zone: both slots hold a token (the one on the swap is the older: VGR2 hadn't taken it yet), only the
       feed, only the swap, or none. The most specific rule first. A gripper over the belt (the nominal zone tracks it)
       would then tell the dead zone of a token it already counts from the light barriers: the zone waits for it. */
    state def ZoneCB_NominalToDeadCB :> MissionMigration {
        attribute :>> machine = "ZoneCB";
        attribute :>> fromMission = "ZoneMissionCBNominal";
        attribute :>> toMission = "ZoneMissionDeadCB";
        attribute :>> waitStates = ("VGR1PlacingOnFeed", "VGR1PlacingOnFeedTokenMovedToSwap",
            "VGR1PlacingOnFeedAfterSwap", "VGR2PickingLastToken", "VGR2PickingSwapBeforeFeed",
            "VGR2PickingSwapFeedMovedToSwap");

        transition zone_tokensOnSwapAndFeed
            accept when cb.conveyorSensFeed == false and cb.conveyorSensSwap == false
            then ZoneMissionDeadCB::SwapThenFeed;

        transition zone_tokenOnFeed
            accept when cb.conveyorSensFeed == false
            then ZoneMissionDeadCB::TokenOnFeed;

        transition zone_tokenOnSwap
            accept when cb.conveyorSensSwap == false
            then ZoneMissionDeadCB::TokenOnSwap;

        transition zone_empty
            then ZoneMissionDeadCB::Empty;
    }

    doc
    /* VGR1 keeps its other states (setup, standby, picking a color, moving to standby with its token, returning).
       Free, it asks the sorting line which colors wait at its outputs; holding a token, it asks the dead CB zone where
       to place it (waiting in standby too: the dead zone only tells it a slot is free after a pick of VGR2). Over the
       belt, it waits. */
    state def VGR1_NominalToCBFailure :> MissionMigration {
        attribute :>> machine = "REN_MISSION_01/VGR01";
        attribute :>> fromMission = "VacuumGripper1NominalMission";
        attribute :>> toMission = "VacuumGripper1CBFailureMission";
        attribute :>> waitStates = ("PlaceConveyorBeltFeed", "RetractFromCBCMD");

        transition vgr1_idle
            first VacuumGripper1NominalMission::Idle
            then VacuumGripper1CBFailureMission::AskSortingLine;

        // holding a token, waiting for the answer of the nominal CB zone: asks the dead CB zone where to place it
        transition vgr1_picked
            first VacuumGripper1NominalMission::IdlePicked
            then VacuumGripper1CBFailureMission::AskCBSlot;

        // in standby with its token, waiting for the feed: asks the dead CB zone at once
        transition vgr1_waitingInStandby
            first VacuumGripper1NominalMission::WaitForFreeCBSlot
            then VacuumGripper1CBFailureMission::AskCBSlotInStandby;

        transition vgr1_askingInStandby
            first VacuumGripper1NominalMission::SelectCBSlotInStandby
            then VacuumGripper1CBFailureMission::AskCBSlotInStandby;
    }

    doc
    /* VGR2 waited for the conveyor belt to bring the token to the swap. Free, or waiting for the answer of the nominal
       zone, it asks the dead CB zone where to pick a token: the one stuck on the feed first. On its way to the MPS it
       goes on there, its arm retracted; it keeps its other states. Over the belt, it waits. */
    state def VGR2_NominalToCBFailure :> MissionMigration {
        attribute :>> machine = "REN_MISSION_01/VGR02";
        attribute :>> fromMission = "VacuumGripper2NominalMission";
        attribute :>> toMission = "VacuumGripper2CBFailureMission";
        attribute :>> waitStates = ("PickCBswapCMD", "RetractFromCBCMD");

        transition vgr2_idle
            first VacuumGripper2NominalMission::Idle
            then VacuumGripper2CBFailureMission::CheckCB;

        transition vgr2_askingTheZone
            first VacuumGripper2NominalMission::WaitForCBZoneAcquisition
            then VacuumGripper2CBFailureMission::CheckCB;

        // going to the safe position before the MPS: requests the MPS zone at once (the place interrupts the move)
        transition vgr2_toMPS
            first VacuumGripper2NominalMission::StandbyBeforeMPSCMD
            then VacuumGripper2CBFailureMission::CBZoneReleased;
    }

    doc
    /* The operator repaired the conveyor belt: the CB zone and the grippers go back to their nominal missions, then the
       belt restarts (from its initial state, it checks its light barriers: it tells VGR2 of a token at the swap, or
       moves the one at the feed). Each machine waits until it is out of the belt. */
    part cbRepaired : ReplanningStrategy {
        attribute :>> description = "Conveyor belt repaired: the CB zone and the grippers go back to their nominal missions, then the belt restarts";
        attribute :>> migrations = ("ZoneCB_DeadCBToNominal", "VGR1_CBFailureToNominal", "VGR2_CBFailureToNominal", "CB_RestartNominal");
    }

    doc
    /* The nominal zone starts by reading the light barriers (CheckBelt): it knows the tokens left on the feed and the
       swap, and tells the grippers waiting for the belt to ask again. It waits for a gripper over the belt. */
    state def ZoneCB_DeadCBToNominal :> MissionMigration {
        attribute :>> machine = "ZoneCB";
        attribute :>> fromMission = "ZoneMissionDeadCB";
        attribute :>> toMission = "ZoneMissionCBNominal";
        attribute :>> waitStates = ("VGR1PlacingOnSwap", "VGR1PlacingOnSwapAfterFeed", "VGR1PlacingOnFeedAfterSwap",
            "VGR2PickingLastToken", "VGR2PickingFeedBeforeSwap", "VGR2PickingSwapBeforeFeed");

        transition zone_checkBelt
            then ZoneMissionCBNominal::CheckBelt;
    }

    doc
    /* VGR1 keeps its states away from the belt (setup, standby, waiting for a color, picking it, moving to standby
       with its token, returning). Waiting in standby for a slot, it asks the nominal zone for the feed at once. Waiting
       for the answer of the dead zone, or over the belt, it waits. */
    state def VGR1_CBFailureToNominal :> MissionMigration {
        attribute :>> machine = "REN_MISSION_01/VGR01";
        attribute :>> fromMission = "VacuumGripper1CBFailureMission";
        attribute :>> toMission = "VacuumGripper1NominalMission";
        attribute :>> waitStates = ("SelectCBSlot", "PlaceConveyorBeltCMD", "RetractFromCBCMD");

        transition vgr1_waitingInStandby
            first VacuumGripper1CBFailureMission::WaitForFreeCBSlot
            then VacuumGripper1NominalMission::AskCBSlotInStandby;

        transition vgr1_askingInStandby
            first VacuumGripper1CBFailureMission::SelectCBSlotInStandby
            then VacuumGripper1NominalMission::AskCBSlotInStandby;
    }

    doc
    /* VGR2 keeps its states away from the belt (setup, standby, on its way to the MPS). Free, it checks the swap at
       once (CheckSwap). Waiting for the answer of the dead zone, or over the belt, it waits. */
    state def VGR2_CBFailureToNominal :> MissionMigration {
        attribute :>> machine = "REN_MISSION_01/VGR02";
        attribute :>> fromMission = "VacuumGripper2CBFailureMission";
        attribute :>> toMission = "VacuumGripper2NominalMission";
        attribute :>> waitStates = ("SelectCBSlot", "PickCBCMD", "RetractFromCBCMD");

        transition vgr2_free
            first VacuumGripper2CBFailureMission::Idle
            then VacuumGripper2NominalMission::CheckSwap;
    }

    doc
    /* The belt was stopped: its nominal mission starts again, from its initial state. */
    state def CB_RestartNominal :> MissionMigration {
        attribute :>> machine = "REN_MISSION_01/CB01";
        attribute :>> toMission = "ConveyorBeltNominalMission";
    }
}
