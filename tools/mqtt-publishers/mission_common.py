
import json
import os
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

DEFAULT_BROKER = "mbdo-server.local"
DEFAULT_PORT = 1883

# Topics are the ones republished by the platform data-gateway-service
# (gateway/<setup>/<MachineModel>/<attribute>), so the DT can be driven without PLCs or gateway.
# Set SETUP=REN_1S1V_1C1V_1H1M_02 to target the Rennes setup (the twin accepts any setup name).
SETUP = os.environ.get("SETUP", "VIRTUAL_1C1H1M1S2V_01")
GATEWAY = f"gateway/{SETUP}"

TOPICS = {

    "sl.sensor_SL_in":    f"{GATEWAY}/SLSystemModel/sortingLineSensInputLightBarrier",
    "sl.sensor_SL_blue":  f"{GATEWAY}/SLSystemModel/sortingLineSensBlueLightBarrier",
    "sl.sensor_SL_white": f"{GATEWAY}/SLSystemModel/sortingLineSensWhiteLightBarrier",
    "sl.sensor_SL_red":   f"{GATEWAY}/SLSystemModel/sortingLineSensRedLightBarrier",

    "cb.conveyorSensFeed": f"{GATEWAY}/CBSystemModel/conveyorSensFeed",
    "cb.conveyorSensSwap": f"{GATEWAY}/CBSystemModel/conveyorSensSwap",

    "vgr1.vacuumActCompressorOn": f"{GATEWAY}/VGRSystemModel_01/vacuumActCompressorOn",
    "vgr1.vacuumActValve":        f"{GATEWAY}/VGRSystemModel_01/vacuumActValve",

    "vgr2.vacuumActCompressorOn": f"{GATEWAY}/VGRSystemModel_02/vacuumActCompressorOn",
    "vgr2.vacuumActValve":        f"{GATEWAY}/VGRSystemModel_02/vacuumActValve",

    "mpo.sensor_MPS_in":  f"{GATEWAY}/MPSystemModel/sensor_MPS_in",
    "mpo.sensor_MPS_out": f"{GATEWAY}/MPSystemModel/sensor_MPS_out",

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
