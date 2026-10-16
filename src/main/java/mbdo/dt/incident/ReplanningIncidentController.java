package mbdo.dt.incident;

import mbdo.dt.orchestrator.DtEngine;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/replanning-incidents")
public class ReplanningIncidentController {

    private final ReplanningIncidentRegistryService registryService;
    private final ReplanningIncidentSseService sseService;
    private final DtEngine dtEngine;

    public ReplanningIncidentController(
            ReplanningIncidentRegistryService registryService,
            ReplanningIncidentSseService sseService,
            DtEngine dtEngine
    ) {
        this.registryService = registryService;
        this.sseService = sseService;
        this.dtEngine = dtEngine;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamIncidents() {
        return sseService.registerClient();
    }

    @GetMapping("/history")
    public List<ReplanningIncidentResponse> getHistory() {
        return registryService.getHistory();
    }

    @GetMapping("/current")
    public ResponseEntity<ReplanningIncidentResponse> getCurrent() {
        return registryService.getCurrentIncident()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<ReplanningIncidentResponse> resolve(
            @PathVariable long id,
            @RequestBody ResolveIncidentRequest request
    ) {
        String comment = request == null ? null : request.getComment();

        return registryService.getOriginatingContext(id)
                .flatMap(context -> dtEngine.resolveDisruption(context, comment))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
