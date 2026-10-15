package ZonesSystem {
    private import Common::Messages::*;
    private import Common::*;

    package ZonesMessages {
        item def AcquireRequestBufferedMessage specializes BufferedMessage {
            attribute sender : Machine;
        }

        item def AcquireResponseEventMessage specializes EventMessage;
        item def ReleaseRequestEventMessage specializes EventMessage;

        // Conveyor belt zone (ZoneMissionCBNominal): knows whether a token is on the feed and on the swap, and lets one
        // gripper at a time over the belt. VGR1 holding a token asks for the feed (CBSlotRequest), answered PlaceOnFeed
        // or NoCBSlotFree, then CBSlotFree once the feed is usable; it sends TokenPlacedOnCB once out of the belt. VGR2
        // asks for the token at the swap (CBTokenRequest), answered PickFromSwap or NoTokenOnCB, then TokenOnCB once
        // VGR1 left the belt; it sends TokenPickedFromCB once out of the belt.
        item def CBSlotRequestBufferedMessage specializes BufferedMessage;
        item def PlaceOnFeedEventMessage specializes EventMessage;
        item def NoCBSlotFreeEventMessage specializes EventMessage;
        item def CBSlotFreeEventMessage specializes EventMessage;
        item def TokenPlacedOnCBBufferedMessage specializes BufferedMessage;
        item def CBTokenRequestBufferedMessage specializes BufferedMessage;
        item def PickFromSwapEventMessage specializes EventMessage;
        item def NoTokenOnCBEventMessage specializes EventMessage;
        item def TokenOnCBEventMessage specializes EventMessage;
        item def TokenPickedFromCBBufferedMessage specializes BufferedMessage;

        calc def GetMachine { 
            metadata Workaround{description="SysOn does not currently support using the member access syntax (request.sender) in the 'in' clause of an action or  in the 'send to' statement.";}
            doc /* This calculation extracts the sender machine from the AcquireRequestBufferedMessage. It is used as a workaround for current limitations in SysOn regarding direct access to message attributes in action inputs and send statements. */
            in value : ZonesMessages::AcquireRequestBufferedMessage; 
            return : Machine = value.sender;
        }
    }

    state def Zone {
        private import ZonesMessages::*;
        entry;
        then IdleFree;

        state IdleFree;
        accept request : AcquireRequestBufferedMessage
            // do send new AcquireResponseEventMessage() to request.sender
            do send new AcquireResponseEventMessage() to GetMachine(request)            
            then IdleBusy;

        state IdleBusy;
        accept request : ReleaseRequestEventMessage then IdleFree;
    }

    doc
    /* Zone of the conveyor belt in the nominal production: knows whether a token is on the feed and on the swap, and
       lets one gripper at a time over the belt. VGR1 only places on a free feed, VGR2 only picks at the swap. The belt
       (ConveyorBeltNominalMission) sends FeedFree once it moved the token from the feed to the swap. */
    state def ZoneMissionCBNominal {
        private import ZonesMessages::*;
        private import ConveyorBeltSystem::ConveyorBeltMessages::*;
        // the grippers send their requests to the zone, and wait for its answers on it
        in zone : ZonesSystem::Zone;
        in conveyorBelt : ConveyorBeltSystem::ConveyorBelt::ConveyorBeltMachine;

        // reads the light barriers of the belt: sends SwapBusy for a token at the swap, then TokenAtFeed for a token at
        // the feed (they lead Empty to the right state); then CBSlotFree if the feed is free, TokenOnCB if a token is
        // at the swap, for a gripper waiting (after a migration)
        action checkBelt;

        entry;
        then CheckBelt;

        state CheckBelt;
        transition first CheckBelt do checkBelt then Empty;

        state Empty;
        accept CBSlotRequestBufferedMessage do send new PlaceOnFeedEventMessage() to zone then VGR1PlacingOnFeed;
        accept CBTokenRequestBufferedMessage do send new NoTokenOnCBEventMessage() to zone then Empty;
        accept SwapBusyBufferedMessage then TokenOnSwap;
        accept TokenAtFeedBufferedMessage then TokenOnFeed;

        state TokenOnFeed;
        accept CBSlotRequestBufferedMessage do send new NoCBSlotFreeEventMessage() to zone then TokenOnFeed;
        accept CBTokenRequestBufferedMessage do send new NoTokenOnCBEventMessage() to zone then TokenOnFeed;
        accept FeedFreeBufferedMessage do send new CBSlotFreeEventMessage() to zone then TokenOnSwap;

        state TokenOnSwap;
        accept CBSlotRequestBufferedMessage do send new PlaceOnFeedEventMessage() to zone then VGR1PlacingOnFeedAfterSwap;
        accept CBTokenRequestBufferedMessage do send new PickFromSwapEventMessage() to zone then VGR2PickingLastToken;
        accept TokenAtFeedBufferedMessage then SwapThenFeed;

        state SwapThenFeed;
        accept CBSlotRequestBufferedMessage do send new NoCBSlotFreeEventMessage() to zone then SwapThenFeed;
        accept CBTokenRequestBufferedMessage do send new PickFromSwapEventMessage() to zone then VGR2PickingSwapBeforeFeed;

        state VGR1PlacingOnFeed;
        accept TokenPlacedOnCBBufferedMessage then TokenOnFeed;
        accept CBTokenRequestBufferedMessage do send new NoTokenOnCBEventMessage() to zone then VGR1PlacingOnFeed;
        accept FeedFreeBufferedMessage then VGR1PlacingOnFeedTokenMovedToSwap;

        state VGR1PlacingOnFeedTokenMovedToSwap;
        accept TokenPlacedOnCBBufferedMessage do send new TokenOnCBEventMessage() to zone then TokenOnSwap;
        accept CBTokenRequestBufferedMessage
            do send new NoTokenOnCBEventMessage() to zone
            then VGR1PlacingOnFeedTokenMovedToSwap;

        state VGR1PlacingOnFeedAfterSwap;
        accept TokenPlacedOnCBBufferedMessage do send new TokenOnCBEventMessage() to zone then SwapThenFeed;
        accept CBTokenRequestBufferedMessage do send new NoTokenOnCBEventMessage() to zone then VGR1PlacingOnFeedAfterSwap;

        state VGR2PickingLastToken;
        accept TokenPickedFromCBBufferedMessage do send new CBSlotFreeEventMessage() to zone then Empty;
        accept CBSlotRequestBufferedMessage do send new NoCBSlotFreeEventMessage() to zone then VGR2PickingLastToken;

        state VGR2PickingSwapBeforeFeed;
        accept TokenPickedFromCBBufferedMessage then TokenOnFeed;
        accept CBSlotRequestBufferedMessage do send new NoCBSlotFreeEventMessage() to zone then VGR2PickingSwapBeforeFeed;
        accept FeedFreeBufferedMessage then VGR2PickingSwapFeedMovedToSwap;

        state VGR2PickingSwapFeedMovedToSwap;
        accept TokenPickedFromCBBufferedMessage do send new CBSlotFreeEventMessage() to zone then TokenOnSwap;
        accept CBSlotRequestBufferedMessage
            do send new NoCBSlotFreeEventMessage() to zone
            then VGR2PickingSwapFeedMovedToSwap;
    }
}
