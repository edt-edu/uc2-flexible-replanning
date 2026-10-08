#!/usr/bin/env python3

import argparse
import sys
import time

from mission_common import add_common_args, make_client, publish, step


def run(client, step_delay):
    step("Placing token at sorting-line input (Token_t0)")
    publish(client, "sl.sensor_SL_in", False, "beam broken - token detected at input")
    time.sleep(step_delay)
    print("  expected: currentState -> TokenAtSortingLineInput")

    step("Ejecting to Blue (Token_t1Blue)")
    publish(client, "sl.sensor_SL_in", True, "input clear - token has moved past")
    publish(client, "sl.sensor_SL_blue", False, "beam broken - token detected at Blue output")
    time.sleep(step_delay)
    print("  expected: currentState -> TokenAtSortingLineBlueOutput")

    step("VGR1 grips the token at Blue output (Token_t2Blue)")
    publish(client, "vgr1.rotEncoder", 2175, "VGR1 at its SL_OUTPUT_BLUE named position")
    publish(client, "vgr1.armEncoder", 1500)
    publish(client, "vgr1.vacuumActCompressorOn", True)
    publish(client, "vgr1.vacuumActValve", True)
    publish(client, "sl.sensor_SL_blue", True, "clear - token picked up off the sensor")
    time.sleep(step_delay)
    print("  expected: currentState -> TokenPickedUpByVGR1")

    step("VGR1 drops the token at the conveyor-belt feed (Token_t3)")
    publish(client, "vgr2.vacuumActCompressorOn", False, "VGR2 idle, waiting")
    publish(client, "vgr2.vacuumActValve", False)
    publish(client, "cb.conveyorSensFeed", False, "feed still clear - drop about to happen")
    time.sleep(step_delay)
    print("  expected: currentState -> TokenDroppedOffAtConveyorBeltFeed")
    print("  (ConveyorBeltFeedDepartureLimit = 10s starts now - the next step below is well under that)")

    step("Conveyor carries the token from feed to swap (Token_t4)")
    publish(client, "cb.conveyorSensFeed", True, "feed beam broken - token now on the belt")
    publish(client, "cb.conveyorSensSwap", False, "swap still clear")
    time.sleep(step_delay)
    print("  expected: currentState -> TokenAtConveyorBeltSwap")
    print("  (ConveyorBeltSwapArrivalLimit = 10s starts now - the next step below is well under that)")

    step("VGR2 grips the token at the swap point (Token_t5)")
    publish(client, "vgr2.rotEncoder", 2650, "VGR2 at its ALT_CB (conveyor belt swap) named position")
    publish(client, "vgr2.armEncoder", 1350)
    publish(client, "vgr2.vacuumActCompressorOn", True)
    publish(client, "vgr2.vacuumActValve", True)
    publish(client, "cb.conveyorSensSwap", True, "swap beam broken - token detected")
    time.sleep(step_delay)
    print("  expected: currentState -> TokenPickedUpByVGR2")

    step("VGR2 drops the token at the multi-processing-station input (Token_t6)")
    publish(client, "vgr2.vacuumActCompressorOn", False, "VGR2 released")
    publish(client, "vgr2.vacuumActValve", False)
    publish(client, "mpo.sensor_MPS_in", False, "MPS input still clear - drop about to happen")
    time.sleep(step_delay)
    print("  expected: currentState -> TokenDroppedOffAtMPOInput")

    step("Token arrives at the multi-processing-station output side (Token_t7)")
    publish(client, "mpo.sensor_MPS_in", True, "MPS input beam broken - token detected")
    publish(client, "mpo.sensor_MPS_out", False)
    time.sleep(step_delay)
    print("  expected: currentState -> TokenAtMPOOutput  <-- mission complete, no anomaly")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    add_common_args(parser)
    parser.add_argument("--step-delay", type=float, default=1.5,
                         help="Seconds to wait after each step, so the DT visibly reacts before the "
                              "next one (default: 1.5)")
    args = parser.parse_args()

    print(f"Connecting to {args.broker}:{args.port} ...")
    client = make_client(args.broker, args.port, client_id="uc2-nominal-mission-publisher")

    try:
        run(client, args.step_delay)
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(1)
    finally:
        client.loop_stop()
        client.disconnect()

    print("\nDone. Check /dt/runtime-state: currentState should be TokenAtMPOOutput, no anomaly.")


if __name__ == "__main__":
    main()
