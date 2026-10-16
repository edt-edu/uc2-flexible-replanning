# UC2- Flexible Replanning

Digital Twin for the UC2 Flexible Replanning.

This example assumes the setup used is `REN_1S1V_1C1V_1H1M_02` which can be found at <https://github.com/edt-edu/dt-setups/tree/main/REN_Setups/REN_1S1V_1C1V_1H1M_02>.

## Running the project

### Quick start: full virtual stack

If `Flexible-replanning`, `dt-platform`, `dt-setups` and `cps-fischertechnik` are checked out side by side in one parent
folder, the `Makefile` in that parent folder starts everything on the virtual setup `VIRTUAL_1C1H1M1S2V_01` (2 VGRs):
the MQTT broker, InfluxDB, Kafka, the simulated PLC, the gateway, the FactorySCADA (backend + frontend) and this
backend and frontend.

```bash
cd ..            # parent folder containing the four repositories
make setup       # once: conda env for rppmcontroller + npm install for both frontends
make up          # start everything (logs: make attach, stop: make down)
```

See the `README.md` next to that `Makefile` for details. The steps below start each piece by hand.

### Setting environment

Copy `.env.example` to `.env` and fill in your InfluxDB credentials.

### Start services

Start the supporting services for the application to run.

#### MQTT Broker and InfluxDB

Start the supporting services (MQTT broker, InfluxDB):

```bash
docker-compose up -d mqtt-broker influxdb
```

#### Gateway

The gateway ensures the link between the physical platform and the Digital Twin, it should be launched with a specific configuration that tells which machines will be available through it.

The gateway application can be found at <https://github.com/edt-edu/dt-platform/tree/main/components/gateways/data-gateway-service>.

The different configurations can be found at <https://github.com/edt-edu/dt-setups>.

```bash
gradle bootRun --args='--configuration.path=../../../../dt-setups/VIRTUAL_Setups/VIRTUAL_1C1H1M1S2V_01/config/server/gateway/services/gateway/config/configuration.yml'

#TODO
```



### Build the application

```bash
./mvnw clean compile
```

### Run the application

```bash
./mvnw spring-boot:run
```

### Shopfloor view (frontend)

The 2D shopfloor map of the frontend is drawn by the `@mbdo/machines-svg-map` library (`machines-svg-map`, next
to this repository in the parent folder). Until it is packaged, a copy of its browser sources is in
`frontend/src/vendor/machines-svg-map` (imported as `@mbdo/machines-svg-map` through a `paths` alias of
`frontend/tsconfig.json`), with the adaptations to upstream listed in its `README.md`:

- the layout is `frontend/public/assets/shopfloor-layout.json`: machine positions, gateway machine names
  (`telemetryId`), VGR arm calibration (named positions -> SVG element) and token locations;
- the backend forwards every gateway measurement as is on `GET /api/machines/stream` (`machine-attribute` events:
  `machineId`, `attribute`, `value`, `timestamp`), and replays the latest value of each attribute to a new client;
  the library knows how each raw attribute is drawn (active-low light barriers, encoder counts, ...).

```bash
cd frontend
npm install
npm start        # http://localhost:4200, /api proxied to the backend
```
