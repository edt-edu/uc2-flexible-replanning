# Factory Supervision UI & Telemetry Sync

This document describes the 2D shopfloor visualization, its live telemetry sync, and the
replanning-incident notification flow that were merged into this app from `cep-service`
(specifically from that repo's `replanning-implementation` branch, not its default branch).

## Why this exists

`cep-service` had a Kafka/Esper-based CEP rule engine with a full incident-management
dashboard, and — on one branch only — a 2D SVG shopfloor visualization with a
"Maintenance Engineer" / "Factory Manager" role toggle, a live machine-telemetry sync
service, and a replanning-incident popup.

This app (`mbdo.dt` / "uc2-dt") is a fischertechnik digital-twin backend with no Kafka, no
rule engine, and (until this merge) no frontend. It already ingests live factory data over
**MQTT** (`MqttMessageHandler` → `DtEngine.dispatch(DataRequest)`) and already has its own
SysML-based anomaly detection.

Only the shopfloor visualization + telemetry sync + incident-popup pieces were ported, and
they were re-wired onto this app's existing MQTT/anomaly pipeline instead of bringing along
cep-service's Kafka/Esper/rule-engine/Postgres stack. **No new external dependencies were
introduced** — everything added below is plain Java on top of what was already on the
classpath (Spring Web, Jackson), and the frontend only drops dependencies (no `echarts`,
no `flowbite`) relative to its cep-service origin.

## Architecture

```
MQTT (PLC/gateway topics)
  │
  ▼
MqttMessageHandler.applyCloneRules()
  │  builds a DataRequest(topic, modelName, attributeName, value, timestamp)
  ├──► DtEngine.dispatch(request)              existing anomaly/replanning simulation
  │        │
  │        ▼
  │    AnomalyReactionServiceImpl.handleDetectedAnomaly()
  │        │  on a detected AnomalyContext:
  │        ├──► DigitalShadow.recordAnomaly(...)          (existing, unchanged)
  │        └──► NotificationService.notifyAnomaly(...)     (newly implemented)
  │                 │
  │                 ▼
  │             ReplanningIncidentRegistryService.openIncident(...)
  │                 │
  │                 ▼
  │             ReplanningIncidentSseService.broadcastOpened(...)  ──► GET /api/replanning-incidents/stream
  │
  └──► MachineTelemetryBridge.onDataRequest(request)     (new, decoupled from DtEngine)
           │  ShopfloorMachineIdResolver maps (modelName, attributeName) -> (machineId, attribute)
           ▼
       MachineStateRegistryService.applyAttribute(...)
           │
           ▼
       MachineTelemetrySseService.broadcastMachineUpdate(...)  ──► GET /api/machines/stream
```

The Angular frontend (`frontend/`) is a separate app/dev-server that consumes both SSE
streams directly over CORS (see `mbdo.dt.config.WebConfig`) — it is not bundled into the
Spring Boot app's static resources.

## Backend

### `mbdo.dt.telemetry` — shopfloor machine sync

| Class | Purpose |
|---|---|
| `state.MachineState`, `AbstractMachineState`, `GenericMachineState`, `ConveyorBeltState`, `SortingLineState`, `MultiProcessingStationState`, `VacuumGripperState`, `HighBayWarehouseState`, `MachineStateFactory` | Ported from cep-service almost verbatim (as plain Java — no Lombok/springdoc). `MachineStateFactory.create(machineId)` picks the machine type by matching the *prefix* of the id (`CB`, `HBW`, `SL`, `MP`, `VGR`) — which is why the frontend's machine ids (`CBSystemModel`, `HBWSystemModel`, `SLSystemModel`, `MPSystemModel`, `VGRSystemModel_01`/`_02`) work unchanged. |
| `MachineStateRegistryService` | Keeps the last known `MachineState` per machineId, applies incoming `(attribute, value, timestamp)` updates, returns a snapshot once the machine is "ready for frontend". |
| `MachineTelemetrySseService` / `MachineTelemetryController` | `GET /api/machines/stream` — SSE, event name `machine-update`, payload = `MachineStateUpdateResponse` (machineId, machineType, state map, timestamp). |
| `ShopfloorMachineIdResolver` | **The one genuinely new piece.** Maps a `DataRequest` onto `(machineId, attribute)`: either by SysML model name (`REN_1V_01_VGRSystemModel` → `VGRSystemModel_01`) or by the short machine code embedded in compound attribute names produced by the `TokenLocation/...` mapping rules in `configuration.yml` (e.g. `cb.conveyorSensFeed` → machine `CBSystemModel`, attribute `conveyorSensFeed`). **Extend this map whenever a new `dtEventGroups` rule is added to `configuration.yml` for a machine/attribute not covered yet.** |
| `MachineTelemetryBridge` | `@Component` called from `MqttMessageHandler` right after `dtEngine.dispatch(request)`. Resolves, applies, and broadcasts — fully decoupled from `DtEngine`'s own anomaly/replanning simulation. |

**Known deviation from cep-service**: `AbstractMachineState` there only flips `isInitialized`
(and thus becomes visible to the frontend) once it sees a
`machineFeedback: {status: "INITIALIZED_IDLE"}` MQTT payload. This app doesn't send that
payload shape, so `isInitialized` now defaults to `true` at construction. Tighten this again
if/when an equivalent init-status topic gets added to `configuration.yml`.

### `mbdo.dt.incident` — replanning incident notifications

This maps onto the previously-empty `mbdo.dt.services.NotificationService` stub instead of
inventing a parallel mechanism — there is no rule engine here, so incidents are raised
directly from `AnomalyContext` (SysML-model anomaly detection), not from cep-service's
`Rule`/`Incident` JPA entities.

| Class | Purpose |
|---|---|
| `ReplanningIncidentResponse`, `ReplanningIncidentStatus` | Plain DTO mirroring the frontend's `ReplanningIncident` model: `id`, `machineId`, `message`, `requiredAction`, `status` (`OPEN`/`RESOLVED`), `startTime`, `createdAt`, `resolvedAt`, `operatorComment`. |
| `ReplanningIncidentRegistryService` | In-memory, server-authoritative: at most one open incident + a bounded (50) history list. Keeps the originating `AnomalyContext` per incident id so resolution can call `DigitalShadow.recordRecovery`. |
| `ReplanningIncidentSseService` / `ReplanningIncidentController` | `GET /api/replanning-incidents/stream` (SSE, events `incident-opened` / `incident-resolved`), `GET /api/replanning-incidents/history`, `GET /api/replanning-incidents/current`, `POST /api/replanning-incidents/{id}/resolve` (body `{"comment": "..."}`). Resolving also calls the existing `DigitalShadow.recordRecovery(...)`. |

`mbdo.dt.services.impl.NotificationServiceImpl` now implements `notifyAnomaly(AnomalyContext)`
by delegating to the registry + SSE service above, and
`mbdo.dt.services.impl.AnomalyReactionServiceImpl.handleDetectedAnomaly` calls it right after
`digitalShadow.recordAnomaly(...)`.

### CORS

`mbdo.dt.config.WebConfig` allows `http://localhost:*` on `/api/**` so the Angular dev server
(port 4200) can call this app (port 8080) directly without a proxy.

## Frontend (`frontend/`)

A standalone Angular 19 app (own `package.json`/dev server), pruned from cep-service's
frontend down to just the shopfloor-supervision feature:

- `pages/factory-supervision` — the page: navbar with role toggle, the shopfloor layout, and
  the incident popup (only shown for the "Maintenance Engineer" role).
- `components/navbar` — "Maintenance Engineer" / "Factory Manager" role toggle.
- `components/factory-shopfloor-layout` — loads `public/assets/shopfloor.svg`, subscribes to
  `MachineTelemetryStore`, re-renders each machine's SVG group on every update.
- `components/replanning-incident-popup` — shows the current open incident, lets the operator
  submit a corrective-action comment.
- `models/shopfloor/**` — per-machine-type frontend models (`ConveyorBeltModel`,
  `SortingLineModel`, `MultiProcessingStationModel`, `VacuumGripperModel`,
  `HighBayWarehouseModel`) and the `RENNES_FACTORY_MACHINE_MODELS` layout config.
- `services/machine-telemetry.service.ts` + `stores/machine-telemetry.store.ts` — SSE client
  for `/api/machines/stream`.
- `services/replanning-incident.service.ts` + `stores/replanning-incident.store.ts` — SSE
  client for `/api/replanning-incidents/stream`, plus REST for history/current/resolve.
  **Resolving an incident does not update local state directly** — it POSTs, then waits for
  the resulting `incident-resolved` SSE event (which every connected client/role receives), so
  all tabs/roles converge on the same server-authoritative state.

Dropped entirely (unrelated to this feature): the rule-management dashboard (`monitor`/`home`
pages, `rule-form`, `incidents-chart`, `adaptative-chart`, `filter-sidebar`,
`filter-history-controls`, `incident-details`, `relate-hover`), the HAMSTERS task-model
decorators, and the `echarts`/`flowbite` dependencies they pulled in.

## Running it

Backend:
```bash
./mvnw spring-boot:run
```
No new dependencies, so the existing `docker-compose.yml` (InfluxDB + MQTT broker) is enough;
the app also still boots if InfluxDB/MQTT are unavailable.

Frontend:
```bash
cd frontend
npm install
npm start   # http://localhost:4200, already pointed at http://localhost:8080/api
```

## Verifying end-to-end

- `curl -N http://localhost:8080/api/machines/stream` — should print a `connected` event and
  stay open.
- Publish an MQTT message matching one of the `dtEventGroups` rules in `configuration.yml`,
  e.g.:
  ```bash
  mosquitto_pub -h localhost -t 'PLC/1/ConveyorBelt/I1ConveyorBelt01/measurements/input/conveyorSensFeed' \
    -m '{"value":"true","timestamp":1710000000000}'
  ```
  A `machine-update` event for `machineId: CBSystemModel` should appear on the stream above.
- Drive a `DataRequest` sequence that trips the SysML anomaly model (via real PLC/MQTT
  traffic) and confirm `/api/replanning-incidents/stream` emits `incident-opened`; then
  `POST /api/replanning-incidents/{id}/resolve` and confirm `incident-resolved` fires and the
  popup clears in the UI.

This wasn't fully exercised end-to-end at merge time — there was no MQTT broker/`mosquitto_pub`
available in the environment where this was built, so the SSE plumbing and topic-parsing logic
were verified by running the app and compiling, not by an actual publish-through-to-UI pass.
