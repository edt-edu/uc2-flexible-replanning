package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineAttributeUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A burst of machine updates reaches the browser as the latest value of each attribute, not as a backlog. */
class MachineTelemetrySseServiceTest {

    /** Long enough that only the explicit flushes below send anything. */
    private static final long NO_AUTOMATIC_FLUSH_MS = 3_600_000L;

    private final MachineTelemetrySseService service =
            new MachineTelemetrySseService(new MachineAttributeRegistryService(), NO_AUTOMATIC_FLUSH_MS);
    private final List<Object> sent = new ArrayList<>();

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static MachineAttributeUpdate update(String machineId, String attribute, int value) {
        return new MachineAttributeUpdate(machineId, attribute, value, Instant.now());
    }

    private void connectClient() {
        service.register(new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) {
                builder.build().stream()
                        .map(dataWithMediaType -> dataWithMediaType.getData())
                        .filter(MachineAttributeUpdate.class::isInstance)
                        .forEach(sent::add);
            }
        });
    }

    @Test
    void onlyTheLatestValueOfEachAttributeIsSentPerFlush() {
        connectClient();
        for (int rotation = 0; rotation < 100; rotation++) {
            service.broadcastMachineAttribute(update("VGRSystemModel_01", "vacuumSensRotEncoderCounter", rotation));
        }
        service.broadcastMachineAttribute(update("VGRSystemModel_01", "vacuumSensArmEncoderCounter", 5));
        service.broadcastMachineAttribute(update("VGRSystemModel_02", "vacuumSensRotEncoderCounter", 7));

        service.flushPendingMachineUpdates();

        assertEquals(3, sent.size());
        assertTrue(sent.stream().map(MachineAttributeUpdate.class::cast).anyMatch(u ->
                u.machineId().equals("VGRSystemModel_01") && u.attribute().equals("vacuumSensRotEncoderCounter")
                        && u.value().equals(99)));
    }

    @Test
    void aFlushWithoutNewUpdatesSendsNothing() {
        connectClient();
        service.broadcastMachineAttribute(update("VGRSystemModel_01", "vacuumSensRotEncoderCounter", 1));
        service.flushPendingMachineUpdates();
        sent.clear();

        service.flushPendingMachineUpdates();

        assertTrue(sent.isEmpty());
    }
}
