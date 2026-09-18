package mbdo.dt.orchestrator;

import mbdo.dt.services.RuntimeContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DtEngineController {

    private final DtEngine dtEngine;
    private final RuntimeContext runtimeContext;

    public DtEngineController(DtEngine dtEngine, RuntimeContext runtimeContext) {
        this.dtEngine = dtEngine;
        this.runtimeContext = runtimeContext;
    }

    @GetMapping("/dt/status")
    public DtEngineStatus status() {
        return new DtEngineStatus(
                dtEngine.isActive(),
                dtEngine.isDatabaseAvailable()
        );
    }

    @GetMapping("/dt/runtime-state")
    public RuntimeStateView runtimeState() {
        return new RuntimeStateView(
                runtimeContext.getActiveStateMachineId(),
                runtimeContext.getActiveStateMachineName(),
                runtimeContext.getCurrentState(),
                runtimeContext.snapshot()
        );
    }

    public record DtEngineStatus(
            boolean active,
            boolean databaseAvailable
    ) {
    }

    public record RuntimeStateView(
            String activeStateMachineId,
            String activeStateMachineName,
            String currentState,
            Map<String, Object> latestValues
    ) {
    }
}