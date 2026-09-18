
import json
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

DEFAULT_BROKER = "mbdo-server.local"
DEFAULT_PORT = 1883

SITE = "SIM01"

TOPICS = {

    "sl.sensor_SL_in":    f"PLC/{SITE}/SortingLine/I1SortingLine01/measurements/input/sortingLineSensInputLightBarrier",
    "sl.sensor_SL_blue":  f"PLC/{SITE}/SortingLine/I1SortingLine01/measurements/input/sortingLineSensBlueLightBarrier",
    "sl.sensor_SL_white": f"PLC/{SITE}/SortingLine/I1SortingLine01/measurements/input/sortingLineSensWhiteLightBarrier",
    "sl.sensor_SL_red":   f"PLC/{SITE}/SortingLine/I1SortingLine01/measurements/input/sortingLineSensRedLightBarrier",

    "cb.conveyorSensFeed": f"PLC/{SITE}/ConveyorBelt/I1ConveyorBelt01/measurements/input/conveyorSensFeed",
    "cb.conveyorSensSwap": f"PLC/{SITE}/ConveyorBelt/I1ConveyorBelt01/measurements/input/conveyorSensSwap",

    "vgr1.vacuumActCompressorOn": f"PLC/{SITE}/VacuumGripper/I1VacuumGripper01/measurements/output/vacuumActCompressorOn",
    "vgr1.vacuumActValve":        f"PLC/{SITE}/VacuumGripper/I1VacuumGripper01/measurements/output/vacuumActValve",

    "vgr2.vacuumActCompressorOn": f"PLC/{SITE}/VacuumGripper/I1VacuumGripper02/measurements/output/vacuumActCompressorOn",
    "vgr2.vacuumActValve":        f"PLC/{SITE}/VacuumGripper/I1VacuumGripper02/measurements/output/vacuumActValve",

    "mpo.sensor_MPS_in":  f"PLC/{SITE}/MultiProcessing/I1MultiProcessing01/measurements/input/sensor_MPS_in",
    "mpo.sensor_MPS_out": f"PLC/{SITE}/MultiProcessing/I1MultiProcessing01/measurements/input/sensor_MPS_out",

    "encoderTolerance":                   "TEST/TokenLocation/positioning/encoderTolerance",
    "vgr1.3DPosition":                    "TEST/TokenLocation/positioning/vgr1_3DPosition",
    "vgr1.expected3DPositionBlueOutput":  "TEST/TokenLocation/positioning/vgr1_expected3DPositionBlueOutput",
    "vgr1.expected3DPositionWhiteOutput": "TEST/TokenLocation/positioning/vgr1_expected3DPositionWhiteOutput",
    "vgr1.expected3DPositionRedOutput":   "TEST/TokenLocation/positioning/vgr1_expected3DPositionRedOutput",
    "vgr2.3DPosition":                    "TEST/TokenLocation/positioning/vgr2_3DPosition",
    "vgr2.expected3DPositionCBSwap":      "TEST/TokenLocation/positioning/vgr2_expected3DPositionCBSwap",
}


def make_client(broker, port, client_id):
    client = mqtt.Client(client_id=client_id, protocol=mqtt.MQTTv311)
    client.connect(broker, port, keepalive=30)
    client.loop_start()
    return client


def publish(client, attribute, value, note=""):
    if attribute not in TOPICS:
        raise KeyError(f"No topic mapping for attribute '{attribute}' - see TOPICS in mission_common.py")

    topic = TOPICS[attribute]
    payload = json.dumps({
        "value": value,

        "timestamp": datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z"),
    })
    client.publish(topic, payload, qos=1)
    suffix = f"  # {note}" if note else ""
    print(f"  -> {attribute:38s} = {str(value):6s}  [{topic}]{suffix}")


def add_common_args(parser):
    parser.add_argument("--broker", default=DEFAULT_BROKER,
                         help=f"MQTT broker host (default: {DEFAULT_BROKER})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                         help=f"MQTT broker port (default: {DEFAULT_PORT})")
    return parser


def step(label):
    print(f"\n=== {label} ===")
