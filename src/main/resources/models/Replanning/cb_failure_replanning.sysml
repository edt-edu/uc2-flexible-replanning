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
        attribute :>> triggerConstraint = "ConveyorBeltFeedDepartureLimit";
        attribute :>> stoppedMachines = ("REN_MISSION_01/CB01");
        attribute :>> migrations = ("ZoneCB_NominalToDeadCB", "VGR1_NominalToCBFailure", "VGR2_NominalToCBFailure");
    }

    doc
    /* The dead CB zone starts in the state the belt's light barriers (active-low) give, whatever the state of the
       nominal zone (free, or held by a gripper): both slots hold a token (the one on the swap is the older: VGR2 hadn't
       taken it yet), only the feed, only the swap, or none. The most specific rule first. */
    state def ZoneCB_NominalToDeadCB :> MissionMigration {
        attribute :>> machine = "ZoneCB";
        attribute :>> fromMission = "ZoneMissionCBNominal";
        attribute :>> toMission = "ZoneMissionDeadCB";

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
    /* VGR1 keeps its other states (setup, standby, picking a color, back from the belt, returning). Free, it asks the
       sorting line which colors wait at its outputs; holding a token, it asks the dead CB zone where to place it.
       Placing on the feed (the nominal mission has no such state) or retracting from it (its CB failure state would
       tell the dead CB zone of a token it already counts from the light barriers), it waits. */
    state def VGR1_NominalToCBFailure :> MissionMigration {
        attribute :>> machine = "REN_MISSION_01/VGR01";
        attribute :>> fromMission = "VacuumGripper1NominalMission";
        attribute :>> toMission = "VacuumGripper1CBFailureMission";
        attribute :>> waitStates = ("RetractFromCBCMD");

        transition vgr1_idle
            first VacuumGripper1NominalMission::Idle
            then VacuumGripper1CBFailureMission::AskSortingLine;

        // holding a token, waiting for the nominal CB zone: asks the dead CB zone where to place it
        transition vgr1_picked
            first VacuumGripper1NominalMission::IdlePicked
            then VacuumGripper1CBFailureMission::AskCBSlot;
    }

    doc
    /* VGR2 waited for the conveyor belt to bring the token to the swap. Free, it asks the dead CB zone where to pick a
       token: the one stuck on the feed first. On its way to the MPS it goes on there, its arm retracted; it keeps its
       other states. Retracting from the swap it waits (its CB failure state would tell the dead CB zone of a pick it
       already counts from the light barriers). */
    state def VGR2_NominalToCBFailure :> MissionMigration {
        attribute :>> machine = "REN_MISSION_01/VGR02";
        attribute :>> fromMission = "VacuumGripper2NominalMission";
        attribute :>> toMission = "VacuumGripper2CBFailureMission";
        attribute :>> waitStates = ("RetractFromCBCMD");

        transition vgr2_idle
            first VacuumGripper2NominalMission::Idle
            then VacuumGripper2CBFailureMission::CheckCB;

        // going to the safe position before the MPS: requests the MPS zone at once (the place interrupts the move)
        transition vgr2_toMPS
            first VacuumGripper2NominalMission::StandbyBeforeMPSCMD
            then VacuumGripper2CBFailureMission::CBZoneReleased;
    }
}
