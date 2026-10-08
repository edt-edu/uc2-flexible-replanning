package token_definitions {

    private import ScalarValues::*;
    private import ScalarFunctions::*;
    private import CollectionFunctions::*;

    item def Token;

    item t1 : Token;
    item t2 : Token;

    package TokenSequencer {

        private import ScalarValues::*;
        private import ScalarFunctions::*;
        private import CollectionFunctions::*;
        private import token_definitions::*;

        part def TokenSequencer {
            ref item tokens : Token[0..*] ordered;
            attribute index : Positive := 1;
        }

        calc def isNextToken {
            in sequencer : TokenSequencer;
            in token : Token;

            return : Boolean =
                sequencer.index <= size(sequencer.tokens)
                and sequencer.tokens#sequencer.index == token;
        }

        action def takeNextToken {
            inout sequencer : TokenSequencer;
            out token : Token;

            first start;
            then assign token := sequencer.tokens#sequencer.index;
            then assign sequencer.index := sequencer.index + 1;
            then done;
        }
    }

    package TokenLocation {

        private import MultiProcessingStationSystem::*;
        private import SortingLineSystem::*;
        private import ConveyorBeltSystem::*;
        private import VacuumGripperSystem::*;
        private import Common::CalcDefinitions::*;
        private import ISQ::*;

        private import token_definitions::*;
        private import token_definitions::TokenSequencer::*;

        part tokenSequencerInstance : TokenSequencer {
            ref item :>> tokens = (t1, t2);
        }

        state def TokenLocationFlow {

            in token : Token;
            in tokenSequencer : TokenSequencer;

            in vgr1 : VacuumGripperSystem::VacuumGripper::VacuumGripperMachine;
            in vgr2 : VacuumGripperSystem::VacuumGripper::VacuumGripperMachine;
            in cb   : ConveyorBeltSystem::ConveyorBelt::ConveyorBeltMachine;
            in sl   : SortingLineSystem::SortingLine::SortingLineMachine;
            in mpo  : MultiProcessingStationSystem::MultiProcessingStation::MultiProcessingStationMachine;

            state WaitingForTokenAtSortingLineInput;
            state TokenAtSortingLineInput;

            state TokenAtSortingLineBlueOutput;
            state TokenAtSortingLineWhiteOutput;
            state TokenAtSortingLineRedOutput;

            state TokenPickedUpByVGR1;
            state TokenDroppedOffAtConveyorBeltFeed;
            state TokenAtConveyorBeltSwap;
            state TokenPickedUpByVGR2;
            state TokenDroppedOffAtMPOInput;
            state TokenAtMPOOutput;

            entry; then WaitingForTokenAtSortingLineInput;

            transition Token_t0
                first WaitingForTokenAtSortingLineInput
                accept when sl.sensor_SL_in == false
                    and isNextToken(tokenSequencer, token)
                do action takeToken : takeNextToken {
                    inout sequencer = tokenSequencer;
                    out token;
                }
                then TokenAtSortingLineInput;

            transition Token_t1Blue
                first TokenAtSortingLineInput
                accept when sl.sensor_SL_in == true
                    and sl.sensor_SL_blue == false
                then TokenAtSortingLineBlueOutput;

            transition Token_t1White
                first TokenAtSortingLineInput
                accept when sl.sensor_SL_in == true
                    and sl.sensor_SL_white == false
                then TokenAtSortingLineWhiteOutput;

            transition Token_t1Red
                first TokenAtSortingLineInput
                accept when sl.sensor_SL_in == true
                    and sl.sensor_SL_red == false
                then TokenAtSortingLineRedOutput;

            // Picked up at the VGR's named position SL_OUTPUT_BLUE: rotation and arm encoders within 100 counts of it (the
            // vertical axis moves during the pick). Keep in sync with the calibrated named positions (dt-setups).
            transition Token_t2Blue
                first TokenAtSortingLineBlueOutput
                accept when sl.sensor_SL_blue == true
                    and vgr1.vacuumActCompressorOn == true
                    and vgr1.vacuumActValve == true
                    and IsInTolerance(vgr1.rotEncoder, 2175, 100)
                    and IsInTolerance(vgr1.armEncoder, 1500, 100)
                then TokenPickedUpByVGR1;

            // Picked up at the VGR's named position SL_OUTPUT_WHITE: rotation and arm encoders within 100 counts of it (the
            // vertical axis moves during the pick). Keep in sync with the calibrated named positions (dt-setups).
            transition Token_t2White
                first TokenAtSortingLineWhiteOutput
                accept when sl.sensor_SL_white == true
                    and vgr1.vacuumActCompressorOn == true
                    and vgr1.vacuumActValve == true
                    and IsInTolerance(vgr1.rotEncoder, 2400, 100)
                    and IsInTolerance(vgr1.armEncoder, 500, 100)
                then TokenPickedUpByVGR1;

            // Picked up at the VGR's named position SL_OUTPUT_RED: rotation and arm encoders within 100 counts of it (the
            // vertical axis moves during the pick). Keep in sync with the calibrated named positions (dt-setups).
            transition Token_t2Red
                first TokenAtSortingLineRedOutput
                accept when sl.sensor_SL_red == true
                    and vgr1.vacuumActCompressorOn == true
                    and vgr1.vacuumActValve == true
                    and IsInTolerance(vgr1.rotEncoder, 2270, 100)
                    and IsInTolerance(vgr1.armEncoder, 970, 100)
                then TokenPickedUpByVGR1;

            transition Token_t3
                first TokenPickedUpByVGR1
                accept when cb.conveyorSensFeed == false
                    and vgr2.vacuumActCompressorOn == false
                    and vgr2.vacuumActValve == false
                then TokenDroppedOffAtConveyorBeltFeed;

            transition Token_t4
                first TokenDroppedOffAtConveyorBeltFeed
                accept when cb.conveyorSensFeed == true
                    and cb.conveyorSensSwap == false
                then TokenAtConveyorBeltSwap;

            // Picked up at the VGR's named position ALT_CB (conveyor belt swap): rotation and arm encoders within 100 counts of it (the
            // vertical axis moves during the pick). Keep in sync with the calibrated named positions (dt-setups).
            transition Token_t5
                first TokenAtConveyorBeltSwap
                accept when cb.conveyorSensSwap == true
                    and vgr2.vacuumActCompressorOn == true
                    and vgr2.vacuumActValve == true
                    and IsInTolerance(vgr2.rotEncoder, 2650, 100)
                    and IsInTolerance(vgr2.armEncoder, 1350, 100)
                then TokenPickedUpByVGR2;

            transition Token_t6
                first TokenPickedUpByVGR2
                accept when mpo.sensor_MPS_in == false
                    and vgr2.vacuumActCompressorOn == false
                    and vgr2.vacuumActValve == false
                then TokenDroppedOffAtMPOInput;

            transition Token_t7
                first TokenDroppedOffAtMPOInput
                accept when mpo.sensor_MPS_in == true
                    and mpo.sensor_MPS_out == false
                then TokenAtMPOOutput;

            transition Token_t8
                first TokenAtMPOOutput
                accept when isNextToken(tokenSequencer, token)
                then WaitingForTokenAtSortingLineInput;
        }

        constraint def StateDurationLimit {
            in constrainedStateName : String;
            in maxDuration :> ISQ::time;
        }


        assert constraint ConveyorBeltFeedDepartureLimit : StateDurationLimit {
            in constrainedStateName = "TokenDroppedOffAtConveyorBeltFeed";
            in maxDuration = 10 [s];
        }

        assert constraint ConveyorBeltSwapArrivalLimit : StateDurationLimit {
            in constrainedStateName = "TokenAtConveyorBeltSwap";
            in maxDuration = 20 [s];
        }
    }
}