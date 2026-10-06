package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.TokenLocationResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/machines")
public class MachineTelemetryController {

    private final MachineTelemetrySseService machineTelemetrySseService;
    private final TokenLocationInferenceService tokenLocationInferenceService;

    public MachineTelemetryController(
            MachineTelemetrySseService machineTelemetrySseService,
            TokenLocationInferenceService tokenLocationInferenceService
    ) {
        this.machineTelemetrySseService = machineTelemetrySseService;
        this.tokenLocationInferenceService = tokenLocationInferenceService;
    }

    /** Current inferred token location, also pushed as "token-update" events on /stream when it changes. */
    @GetMapping("/token")
    public TokenLocationResponse currentTokenLocation() {
        return tokenLocationInferenceService.currentTokenLocation();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMachineTelemetry() {
        return machineTelemetrySseService.registerClient();
    }
}
