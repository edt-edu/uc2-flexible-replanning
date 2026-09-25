# Manual launch: full virtual stack

Starts the Flexible-replanning digital twin and the FactorySCADA by hand, on the virtual setup `VIRTUAL_1C1H1M1S2V_01`
(conveyor belt, sorting line, multiprocessing, high bay, 2 VGRs).

The commands assume the four repositories are checked out side by side:

```
<parent>/
├── Flexible-replanning/    # this repo
├── dt-platform/
├── dt-setups/
└── cps-fischertechnik/
```

**Every block starts from the Flexible-replanning repo root.** Steps 1–3 run detached. Steps 4–9 each keep running, so
give each one its own terminal. Run them in this order.

## Prerequisites

- `docker` with `docker compose`
- JDK 21 (the gateway's Gradle toolchain needs 21; Lombok in the SCADA backend breaks on newer JDKs)
- Node/npm
- conda (commands below assume `~/miniconda3`)

One-time setup:

```bash
# conda env with rppmcontroller installed in editable mode
source ~/miniconda3/etc/profile.d/conda.sh
conda env create -f ../cps-fischertechnik/rppmcontroller/environment.yml   # skip if `conda env list` shows rppmcontroller
conda activate rppmcontroller
pip install -e ../cps-fischertechnik/rppmcontroller --config-settings editable_mode=compat

# frontend dependencies
(cd frontend && npm install)
(cd ../cps-fischertechnik/factoryscada/frontend && npm install)
```

## 1. MQTT broker (:1883)

```bash
docker compose up -d mqtt-broker
```

Docker warns that the `INFLUXDB_*` variables aren't set. That comes from this compose file's own `influxdb` service,
which isn't used here (see step 2), so the warnings are harmless.

## 2. InfluxDB (:8086)

This uses the setup's InfluxDB, not the one in this repo's `docker-compose.yml`: its credentials (org `mbdo`, bucket
`factory`, admin token) are the ones the gateway configuration expects. Both listen on 8086, so only one can run.

```bash
cd ../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/server/gateway
docker compose up -d --wait influxdb2
```

If this fails with `bind source path does not exist`, an old `influxdb2` container from another checkout is in the way.
Add `--force-recreate` (the data volume is kept).

Create the bucket used by the Flexible-replanning backend (only needed once, fails harmlessly if it already exists):

```bash
TOKEN=$(cat ../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/server/gateway/services/influxdb/config/.env.influxdb2-admin-token)
docker exec influxdb2 influx bucket create -o mbdo -t "$TOKEN" -n digital_twin
```

Web UI: <http://localhost:8086> (user `admin`, password in `.env.influxdb2-admin-password` next to the token file).

## 3. Kafka (:9092)

The gateway configuration sets a Kafka broker URL. Without a running broker, each Kafka send blocks the gateway for up
to 60 s.

```bash
cd ../dt-setups/VIRTUAL_Setups/VIRTUAL_shared/kafka
docker compose up -d --wait kafka
```

## 4. Simulated PLC (TCP 6001 / 6011)

```bash
source ~/miniconda3/etc/profile.d/conda.sh
conda activate rppmcontroller
cd ../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/PLC1
python3 SimulatedMachines_Controller.py
```

It publishes machine state to MQTT under `PLC/virtualmachines-plc-1/...` and waits for commands from the SCADA backend.

## 5. Gateway

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.9-tem      # or any JDK 21
CONF="$(realpath ../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/server/gateway/services/gateway/config/configuration.yml)"
cd ../dt-platform/components/gateways/data-gateway-service
./gradlew bootRun --console=plain --args="--configuration.path=$CONF"
```

The configuration path must be absolute (hence `realpath`), because Gradle runs the app from the gateway directory.
The gateway republishes PLC topics to `gateway/VIRTUAL_1C1H1M1S2V_01/...` and records them in InfluxDB (`factory`)
and Kafka (`events`). The Flexible-replanning backend only consumes these `gateway/...` topics, never the `PLC/...`
ones, so the gateway must be running for the digital twin to receive any data.

## 6. Flexible-replanning backend (:8080)

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.9-tem
export INFLUX_ORG=mbdo
export INFLUX_BUCKET=digital_twin
export INFLUX_TOKEN=$(cat ../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/server/gateway/services/influxdb/config/.env.influxdb2-admin-token)
sh ./mvnw spring-boot:run
```

`mvnw` isn't marked executable in the repo, hence `sh ./mvnw`. `INFLUX_URL` and `MQTT_BROKER_URL` default to
localhost, so they don't need to be set.

## 7. Flexible-replanning frontend (:4200)

```bash
cd frontend
npm start
```

Open <http://localhost:4200>. `/api` is proxied to the backend on :8080.

## 8. SCADA backend (:9000)

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.9-tem
SCADA_CONF="$(realpath ../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/server/factoryscada/services/scada-backend/config)"
cd ../cps-fischertechnik/factoryscada/backend
./gradlew bootRun --console=plain --args="--configuration.path=$SCADA_CONF/configuration.yml --missions.parallelized.configuration.path=$SCADA_CONF/missions-parallelized-configuration.yml --logging.file.name=build/scada-backend.log"
```

Start it after the PLC (step 4): it connects to the PLC on 6001/6011 at startup. `--logging.file.name` overrides the
default `/app/config/scada-backend.log`, which only exists in Docker.

## 9. SCADA frontend (:4201)

```bash
cd ../cps-fischertechnik/factoryscada/frontend
npm start -- --port 4201
```

Open <http://localhost:4201> to send commands and missions to the simulated machines. It runs on 4201 because the
Flexible-replanning frontend already uses 4200. It calls the SCADA backend on :9000 directly, so the port change needs
nothing else.

## Stopping

- Steps 4–9: `Ctrl-C` in each terminal.
- Docker services, each from the Flexible-replanning root:

```bash
docker compose stop mqtt-broker
(cd ../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/server/gateway && docker compose stop influxdb2)
(cd ../dt-setups/VIRTUAL_Setups/VIRTUAL_shared/kafka && docker compose stop kafka)
```

## Ports summary

| Port | Service |
|---|---|
| 1883 | MQTT broker |
| 8086 | InfluxDB |
| 9092 | Kafka |
| 6001 / 6011 | Simulated PLC (commands / notifications) |
| 8080 | Flexible-replanning backend |
| 4200 | Flexible-replanning frontend |
| 9000 | SCADA backend |
| 4201 | SCADA frontend |
