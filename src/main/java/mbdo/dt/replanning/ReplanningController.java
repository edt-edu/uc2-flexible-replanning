package mbdo.dt.replanning;

import mbdo.dt.replanning.scada.ScadaClient;
import mbdo.dt.replanning.scada.ScadaException;
import mbdo.dt.replanning.scada.ScadaMachine;
import mbdo.dt.services.ReplanningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The replanning model and the SCADA machines as the replanning sees them, and a dry run of a strategy. A replanning
 * is executed as soon as its anomaly is detected, its incident shows it.
 */
@RestController
@RequestMapping("/api/replanning")
public class ReplanningController {

    private final ScadaClient scada;
    private final ReplanningService replanningService;

    public ReplanningController(ScadaClient scada, ReplanningService replanningService) {
        this.scada = scada;
        this.replanningService = replanningService;
    }

    /** The replanning strategies and mission migrations of the SysML models. */
    @GetMapping("/model")
    public ReplanningModel getModel() {
        return replanningService.getModel();
    }

    /** The SCADA machines: the missions they can run, and the mission and state they run. */
    @GetMapping("/scada/machines")
    public List<ScadaMachine> getScadaMachines() {
        return scada.machines();
    }

    /** What the strategy would do now, without doing it. */
    @PostMapping("/strategies/{name}/plan")
    public ReplanningPlan plan(@PathVariable String name) {
        return replanningService.planStrategy(name);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ScadaException.class)
    public ResponseEntity<Map<String, String>> scadaUnavailable(ScadaException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
