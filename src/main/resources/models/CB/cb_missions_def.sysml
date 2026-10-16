package ConveyorBeltMission {
    private import ConveyorBeltSystem::*;
    private import ConveyorBeltSystem::ConveyorBeltCommands::*;
    private import ConveyorBeltSystem::ConveyorBeltMessages::*;

    doc
    /* Nominal mission for the conveyor belt */
    state def ConveyorBeltNominalMission {
        in conveyorBelt : ConveyorBeltSystem::ConveyorBelt::ConveyorBeltMachine;
        in vacuumGripper1 : VacuumGripperSystem::VacuumGripper::VacuumGripperMachine;
        in vacuumGripper2 : VacuumGripperSystem::VacuumGripper::VacuumGripperMachine;
        // the CB zone (ZoneMissionCBNominal) counts the tokens on the feed and the swap: told when a token moved
        in zoneCB : ZonesSystem::Zone;

        // reads the light barriers: a token at the swap -> sends SwapBusy to vacuumGripper2; else a token at the
        // feed -> sends TokenAtFeed to conveyorBelt
        action checkBelt;

        entry;
        then CheckBelt;

        // (Re)started with tokens already on the belt (e.g. after a repair): the light barrier edges that Idle
        // waits for already happened, so check them once.
        state CheckBelt;
        transition first CheckBelt do checkBelt then Idle;

        state Idle;
        accept when conveyorBelt.conveyorSensFeed == true and conveyorBelt.conveyorSensSwap == false
            do conveyorBelt.moveToSensor
            then MovingToSensor;
        accept TokenAtFeedBufferedMessage
            do conveyorBelt.moveToSensor
            then MovingToSensor;

        // The token left the feed and waits at the swap: the CB zone is told first (the feed is free for VGR1 again),
        // then VGR2, which asks the zone to pick it.
        state MovingToSensor;
        accept CommandSuccessEventMessage
            do action {
                send new FeedFreeBufferedMessage() to zoneCB;
                send new FeedFreeBufferedMessage() to vacuumGripper1;
                send new SwapBusyBufferedMessage() to vacuumGripper2;
            }
            then Idle;
    }
}
