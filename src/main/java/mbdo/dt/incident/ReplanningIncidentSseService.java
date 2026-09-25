package mbdo.dt.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ReplanningIncidentSseService {

    private static final Logger logger = LoggerFactory.getLogger(ReplanningIncidentSseService.class);

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
                    .data("Replanning incident SSE connection established"));
        } catch (Exception e) {
            logger.warn("Error sending initial replanning incident SSE event", e);
            emitters.remove(emitter);
        }

        return emitter;
    }

    public void broadcastOpened(ReplanningIncidentResponse incident) {
        broadcast("incident-opened", incident);
    }

    public void broadcastResolved(ReplanningIncidentResponse incident) {
        broadcast("incident-resolved", incident);
    }

    private void broadcast(String eventName, ReplanningIncidentResponse incident) {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(incident));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }
}
