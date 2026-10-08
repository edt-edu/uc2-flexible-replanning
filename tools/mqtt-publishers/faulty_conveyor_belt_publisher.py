#!/usr/bin/env python3

import argparse
import sys
import time

from mission_common import add_common_args, make_client, publish, step


def run(client, step_delay, stall_seconds, heartbeat_interval):
    step("Placing token at sorting-line input (Token_t0)")
    publish(client, "sl.sensor_SL_in", False)
    time.sleep(step_delay)

    step("Ejecting to Blue (Token_t1Blue)")
    publish(client, "sl.sensor_SL_in", True)
    publish(client, "sl.sensor_SL_blue", False)
    time.sleep(step_delay)

    step("VGR1 grips the token at Blue output (Token_t2Blue)")
    publish(client, "vgr1.rotEncoder", 2175)  # VGR1 at its SL_OUTPUT_BLUE named position
    publish(client, "vgr1.armEncoder", 1500)
    publish(client, "vgr1.vacuumActCompressorOn", True)
    publish(client, "vgr1.vacuumActValve", True)
    publish(client, "sl.sensor_SL_blue", True)
    time.sleep(step_delay)

    step("VGR1 drops the token at the conveyor-belt feed (Token_t3)")
    publish(client, "vgr2.vacuumActCompressorOn", False)
    publish(client, "vgr2.vacuumActValve", False)
    publish(client, "cb.conveyorSensFeed", False)
    time.sleep(step_delay)
    print("  expected: currentState -> TokenDroppedOffAtConveyorBeltFeed")

    step(f"Simulating a jammed belt: withholding cb.conveyorSensFeed=true for {stall_seconds}s "
         f"(limit is 20s)")
    elapsed = 0.0
    while elapsed < stall_seconds:
        time.sleep(heartbeat_interval)
        elapsed += heartbeat_interval
        publish(client, "cb.conveyorSensFeed", False,
                f"heartbeat, {elapsed:.1f}s elapsed since entering TokenDroppedOffAtConveyorBeltFeed")

    print(f"\n  {stall_seconds}s elapsed with no belt movement - "
          f"ConveyorBeltFeedDepartureLimit (20s) should have fired during the heartbeats above.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    add_common_args(parser)
    parser.add_argument("--step-delay", type=float, default=1.5,
                         help="Seconds to wait after each early step before moving on (default: 1.5)")
    parser.add_argument("--stall-seconds", type=float, default=24.0,
                         help="How long to hold the token at the feed past the 20s limit (default: 24)")
    parser.add_argument("--heartbeat-interval", type=float, default=1.5,
                         help="How often to re-publish the unchanged feed reading while stalled, so "
                              "the DT gets a chance to notice the elapsed time (default: 1.5)")
    args = parser.parse_args()

    print(f"Connecting to {args.broker}:{args.port} ...")
    client = make_client(args.broker, args.port, client_id="uc2-faulty-cb-publisher")

    try:
        run(client, args.step_delay, args.stall_seconds, args.heartbeat_interval)
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(1)
    finally:
        client.loop_stop()
        client.disconnect()

    print("\nDone. Check the app console for a 'status=ANOMALY_DETECTED' trace line quoting")
    print("ConveyorBeltFeedDepartureLimit, and GET /dt/runtime-state (currentState frozen at")
    print("TokenDroppedOffAtConveyorBeltFeed). Detection now stays latched until the app is")
    print("restarted - there is no live recovery endpoint currently wired (NotificationService")
    print("is an intentionally-empty stub). Restart before running any further test.")


if __name__ == "__main__":
    main()
