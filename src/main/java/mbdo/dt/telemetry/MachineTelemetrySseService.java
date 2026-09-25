package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineStateUpdateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MachineTelemetrySseService {

    private static final Logger logger = LoggerFactory.getLogger(MachineTelemetrySseService.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter registerClient() {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));

        emitters.add(emitter);

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("Machine telemetry SSE connection established"));
        } catch (Exception e) {
            logger.warn("Error sending initial machine SSE event", e);
            emitters.remove(emitter);
        }

        return emitter;
    }

    public void broadcastMachineUpdate(MachineStateUpdateResponse stateUpdate) {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("machine-update")
                        .data(stateUpdate));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }
}
