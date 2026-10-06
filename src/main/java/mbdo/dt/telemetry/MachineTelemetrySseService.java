package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineStateUpdateResponse;
import mbdo.dt.telemetry.dto.TokenLocationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class MachineTelemetrySseService {

    private static final Logger logger = LoggerFactory.getLogger(MachineTelemetrySseService.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final MachineStateRegistryService machineStateRegistry;

    /**
     * Latest not-yet-sent state of each machine. A moving machine changes state far more often than a browser can
     * draw, so only the latest state is sent, every flush interval: the UI shows live values instead of replaying a
     * backlog.
     */
    private final Map<String, MachineStateUpdateResponse> pendingMachineUpdates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flushScheduler;

    public MachineTelemetrySseService(
            MachineStateRegistryService machineStateRegistry,
            @Value("${telemetry.sse-flush-interval-ms:33}") long flushIntervalMs
    ) {
        this.machineStateRegistry = machineStateRegistry;
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "machine-sse-flush");
            thread.setDaemon(true);
            return thread;
        });
        flushScheduler.scheduleAtFixedRate(this::flushPendingMachineUpdates,
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        flushScheduler.shutdownNow();
    }

    public SseEmitter registerClient() {
        return register(new SseEmitter(0L));
    }

    SseEmitter register(SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));

        emitters.add(emitter);

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("Machine telemetry SSE connection established"));
            // a (re)loaded page starts from the latest known machine states, not from the defaults
            for (MachineStateUpdateResponse snapshot : machineStateRegistry.snapshots()) {
                emitter.send(SseEmitter.event()
                        .name("machine-update")
                        .data(snapshot));
            }
        } catch (Exception e) {
            logger.warn("Error sending initial machine SSE event", e);
            emitters.remove(emitter);
        }

        return emitter;
    }

    public void broadcastMachineUpdate(MachineStateUpdateResponse stateUpdate) {
        pendingMachineUpdates.put(stateUpdate.getMachineId(), stateUpdate);
    }

    void flushPendingMachineUpdates() {
        for (String machineId : pendingMachineUpdates.keySet()) {
            MachineStateUpdateResponse stateUpdate = pendingMachineUpdates.remove(machineId);
            if (stateUpdate != null) {
                broadcast("machine-update", stateUpdate);
            }
        }
    }

    public void broadcastTokenUpdate(TokenLocationResponse tokenLocation) {
        broadcast("token-update", tokenLocation);
    }

    private void broadcast(String eventName, Object data) {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }
}
