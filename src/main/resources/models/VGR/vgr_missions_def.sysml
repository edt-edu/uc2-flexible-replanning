package VacuumGripperMissions {
    private import VacuumGripperSystem::*;
    private import VacuumGripperSystem::VacuumGripperCommands::*;
    private import VacuumGripperSystem::VacuumGripperMessages::CommandSuccess;
    private import SortingLineSystem::SortingLineCommands::*;
    private import SortingLineSystem::SortingLineMessages::*;
    private import ZonesSystem::Zone;
    private import ZonesSystem::ZonesMessages::*;
    private import MultiProcessingStationSystem::MultiProcessingStationMessages::*;
    private import ConveyorBeltSystem::ConveyorBeltMessages::*;
    private import ScalarValues::*;
    doc
    /* Nominal mission scenario for VacuumGripper n°1 */
    state def VacuumGripper1NominalMission {
        in vacuumGripper : VacuumGripperSystem::VacuumGripper::VacuumGripperMachine;
        in zoneCB : ZonesSystem::Zone;

        action goToStandby : GoToPosition {
            perform vacuumGripper.goToPosition : GoToPosition {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        action pickColor {
            in color : Color;

            then decide;
                if color == Color::BLUE then pickBlue;
                if color == Color::WHITE then pickWhite;
                if color == Color::RED then pickRed;
        }

        action pickBlue : Pick {
            perform vacuumGripper.pick : Pick {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        action pickWhite : Pick {
            perform vacuumGripper.pick : Pick {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        action pickRed : Pick {
            perform vacuumGripper.pick : Pick {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        action placeConveyoBeltFeed : Place {
            perform vacuumGripper.place : Place {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        // sends TokenRequest to the sorting line
        action askSortingLine;
        // sends CBSlotRequest to zoneCB
        action askCBSlot;

        entry vacuumGripper.setup;
        then SetupCMD;

        state SetupCMD;
        accept CommandSuccessEventMessage do goToStandby then StandbyCMD;

        state StandbyCMD;
        accept CommandSuccessEventMessage then AskSortingLine;

        // Free: ask the sorting line for the colors waiting at its outputs (sorted while VGR1 was busy). It answers
        // when idle, VGR1 taking the first color; a sort in progress ends by announcing its color.
        state AskSortingLine;
        transition first AskSortingLine do askSortingLine then Idle;

        state Idle;
        accept BlueTokenAvailableBufferedMessage do pickBlue then PickColorCMD;
        accept WhiteTokenAvailableBufferedMessage do pickWhite then PickColorCMD;
        accept RedTokenAvailableBufferedMessage do pickRed then PickColorCMD;

        // Holding the token: ask the CB zone (ZoneMissionCBNominal) for the feed, granted only if it is free and VGR2
        // is not over the belt: VGR1 never stacks a token on the feed.
        state PickColorCMD;
        accept CommandSuccessEventMessage
            do send new CBSlotRequestBufferedMessage() to zoneCB
            then IdlePicked;

        // CBSlotFree: the request was lost (the CB zone was not running yet), ask again.
        state IdlePicked;
        accept PlaceOnFeedEventMessage do placeConveyoBeltFeed then PlaceConveyorBeltFeed;
        accept NoCBSlotFreeEventMessage do goToStandby then StandbyWithTokenCMD;
        accept CBSlotFreeEventMessage do send new CBSlotRequestBufferedMessage() to zoneCB then IdlePicked;

        // Refused: wait in standby, holding the token. A CBSlotFree received while moving is dropped: once in standby,
        // VGR1 asks again.
        state StandbyWithTokenCMD;
        accept CommandSuccessEventMessage then AskCBSlotInStandby;

        state AskCBSlotInStandby;
        transition first AskCBSlotInStandby do askCBSlot then SelectCBSlotInStandby;

        state SelectCBSlotInStandby;
        accept PlaceOnFeedEventMessage do placeConveyoBeltFeed then PlaceConveyorBeltFeed;
        accept NoCBSlotFreeEventMessage then WaitForFreeCBSlot;
        accept CBSlotFreeEventMessage then AskCBSlotInStandby;

        state WaitForFreeCBSlot;
        accept CBSlotFreeEventMessage then AskCBSlotInStandby;

        // After the place: retract the arm (out of the belt), tell the CB zone, then go to the safe position.
        state PlaceConveyorBeltFeed;
        accept CommandSuccessEventMessage do vacuumGripper.retractArm then RetractFromCBCMD;

        state RetractFromCBCMD;
        accept CommandSuccessEventMessage
            do send new TokenPlacedOnCBBufferedMessage() to zoneCB
            then CBZoneReleased;

        state CBZoneReleased;
        transition first CBZoneReleased do goToStandby then ReturnToStandbyCMD;

        state ReturnToStandbyCMD;
        accept CommandSuccessEventMessage then AskSortingLine;
    }

    doc
    /* Nominal mission scenario for VacuumGripper n°2 */
    state def VacuumGripper2NominalMission {
        in vacuumGripper : VacuumGripperSystem::VacuumGripper::VacuumGripperMachine;
        in conveyorBelt : ConveyorBeltSystem::ConveyorBelt::ConveyorBeltMachine;
        in zoneCB : ZonesSystem::Zone;
        in zoneMPS : ZonesSystem::Zone;

        action goToStandby : GoToPosition {
            perform vacuumGripper.goToPosition : GoToPosition {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        action pickCBswap : Pick {
            perform vacuumGripper.pick : Pick {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        action placeMPSin : Place {
            perform vacuumGripper.place : Place {
                in targetPosition : Position3D {
                    vertical = 0.0;
                    horizontal = 0.0;
                    rot = 0.0;
                }
            }
        }

        // sends SwapBusy to vacuumGripper if a token waits at the CB swap (light barrier of conveyorBelt)
        action checkSwap;

        entry vacuumGripper.setup;
        then SetupCMD;

        state SetupCMD;
        accept CommandSuccessEventMessage do goToStandby then StandbyCMD;

        state StandbyCMD;
        accept CommandSuccessEventMessage then CheckSwap;

        // Free: the conveyor belt sends SwapBusy only once, when it brings a token to the swap; if VGR2 was busy then
        // (at the MPS, or holding a token waiting for the MPS zone), it missed it. A token waiting at the swap is
        // announced again.
        state CheckSwap;
        transition first CheckSwap do checkSwap then Idle;

        // A token at the swap: ask the CB zone (ZoneMissionCBNominal), refused while VGR1 is over the belt; the zone
        // tells VGR2 (TokenOnCB) once VGR1 left it.
        state Idle;
        accept SwapBusyBufferedMessage
            do send new CBTokenRequestBufferedMessage() to zoneCB
            then WaitForCBZoneAcquisition;
        accept TokenOnCBEventMessage
            do send new CBTokenRequestBufferedMessage() to zoneCB
            then WaitForCBZoneAcquisition;

        // TokenOnCB: the request was lost (the CB zone was not running yet), ask again.
        state WaitForCBZoneAcquisition;
        accept PickFromSwapEventMessage do pickCBswap then PickCBswapCMD;
        accept NoTokenOnCBEventMessage then Idle;
        accept TokenOnCBEventMessage
            do send new CBTokenRequestBufferedMessage() to zoneCB
            then WaitForCBZoneAcquisition;

        // After the pick: retract the arm (out of the belt), tell the CB zone, then go to the safe position.
        state PickCBswapCMD;
        accept CommandSuccessEventMessage do vacuumGripper.retractArm then RetractFromCBCMD;

        state RetractFromCBCMD;
        accept CommandSuccessEventMessage
            do send new TokenPickedFromCBBufferedMessage() to zoneCB
            then CBZoneReleased;

        state CBZoneReleased;
        transition first CBZoneReleased do goToStandby then StandbyBeforeMPSCMD;

        // In standby: request the MPS zone, shared with the MPS (MultiProcessingStationNominalMission). VGR2 holds its
        // token while the MPS is full.
        state StandbyBeforeMPSCMD;
        accept CommandSuccessEventMessage
            do send new AcquireRequestBufferedMessage(sender=vacuumGripper) to zoneMPS
            then WaitForMPSZoneAcquisition;

        state WaitForMPSZoneAcquisition;
        accept AcquireResponseEventMessage do placeMPSin then PlaceMPSinCMD;

        // After the place: retract the arm (out of the MPS), release the MPS zone, then go to the safe position.
        state PlaceMPSinCMD;
        accept CommandSuccessEventMessage do vacuumGripper.retractArm then RetractFromMPSCMD;

        state RetractFromMPSCMD;
        accept CommandSuccessEventMessage
            do send new ReleaseRequestEventMessage() to zoneMPS
            then MPSZoneReleased;

        state MPSZoneReleased;
        transition first MPSZoneReleased do goToStandby then ReturnToStandbyCMD;

        // Back in standby: takes the token already at the swap, or waits for the next one.
        state ReturnToStandbyCMD;
        accept CommandSuccessEventMessage then CheckSwap;
    }
}
