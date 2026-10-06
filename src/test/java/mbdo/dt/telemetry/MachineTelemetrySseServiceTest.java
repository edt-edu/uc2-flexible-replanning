package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineStateUpdateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A burst of machine updates reaches the browser as the latest state of each machine, not as a backlog. */
class MachineTelemetrySseServiceTest {

    /** Long enough that only the explicit flushes below send anything. */
    private static final long NO_AUTOMATIC_FLUSH_MS = 3_600_000L;

    private final MachineTelemetrySseService service =
            new MachineTelemetrySseService(new MachineStateRegistryService(), NO_AUTOMATIC_FLUSH_MS);
    private final List<Object> sent = new ArrayList<>();

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static MachineStateUpdateResponse update(String machineId, int rotation) {
        return new MachineStateUpdateResponse(machineId, "vacuum-gripper", Map.of("rotation", rotation), Instant.now());
    }

    private void connectClient() {
        service.register(new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) {
                builder.build().stream()
                        .map(dataWithMediaType -> dataWithMediaType.getData())
                        .filter(MachineStateUpdateResponse.class::isInstance)
                        .forEach(sent::add);
            }
        });
    }

    @Test
    void onlyTheLatestStateOfEachMachineIsSentPerFlush() {
        connectClient();
        for (int rotation = 0; rotation < 100; rotation++) {
            service.broadcastMachineUpdate(update("VGRSystemModel_01", rotation));
        }
        service.broadcastMachineUpdate(update("VGRSystemModel_02", 7));

        service.flushPendingMachineUpdates();

        assertEquals(2, sent.size());
        assertTrue(sent.stream().anyMatch(u -> ((MachineStateUpdateResponse) u).getMachineId().equals("VGRSystemModel_01")
                && ((MachineStateUpdateResponse) u).getState().get("rotation").equals(99)));
    }

    @Test
    void aFlushWithoutNewUpdatesSendsNothing() {
        connectClient();
        service.broadcastMachineUpdate(update("VGRSystemModel_01", 1));
        service.flushPendingMachineUpdates();
        sent.clear();

        service.flushPendingMachineUpdates();

        assertTrue(sent.isEmpty());
    }
}
