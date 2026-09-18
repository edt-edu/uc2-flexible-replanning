package mbdo.dt.models;

import mbdo.dt.models.SysMLBehavioralModel.StateMachine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks which SysML state machine anomaly detection should watch, given every state machine
 * parsed across the loaded model files. Holds no state of its own - every call is given the
 * parsed models and the preferred name to match, so the selection can't drift out of sync
 * with what's actually loaded.
 */
@Component
public class StateMachineSelector {

    /**
     * Builds a candidate for every state machine found across {@code parsedModels}, then picks
     * one: the sole candidate if there's only one, the sole candidate whose name contains
     * {@code primaryStateMachineName} if there are several, or throws if neither resolves to
     * exactly one.
     */
    public ModelStateMachine select(
            List<SysMLV2Parser.ParsedSysMLModel> parsedModels,
            String primaryStateMachineName
    ) {
        List<ModelStateMachine> candidates = new ArrayList<>();

        for (SysMLV2Parser.ParsedSysMLModel parsedModel : parsedModels) {
            if (parsedModel == null || parsedModel.behavioralModel() == null) {
                continue;
            }

            for (StateMachine stateMachine : parsedModel.behavioralModel().stateMachines()) {
                candidates.add(new ModelStateMachine(
                        parsedModel.structuralModel(),
                        parsedModel.behavioralModel(),
                        stateMachine
                ));
            }
        }

        return selectAnomalyStateMachine(candidates, primaryStateMachineName);
    }

    private ModelStateMachine selectAnomalyStateMachine(
            List<ModelStateMachine> candidates,
            String primaryStateMachineName
    ) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No state machine found in the SysML models.");
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        List<ModelStateMachine> primaryCandidates = candidates.stream()
                .filter(candidate -> candidate.stateMachine().name().contains(primaryStateMachineName))
                .toList();

        if (primaryCandidates.size() == 1) {
            return primaryCandidates.get(0);
        }

        throw new IllegalStateException(
                "Could not determine the anomaly detection state machine from the model. "
                        + "Expected exactly one state machine, or exactly one whose name contains '"
                        + primaryStateMachineName
                        + "' (configurable via anomaly-detection.primary-state-machine)."
        );
    }

    public record ModelStateMachine(
            SysMLStructuralModel structuralModel,
            SysMLBehavioralModel behavioralModel,
            StateMachine stateMachine
    ) {
    }
}
