package mbdo.dt.models;

import mbdo.dt.models.SysMLBehavioralModel.StateMachine;
import mbdo.dt.models.SysMLBehavioralModel.Transition;
import mbdo.dt.utils.RuntimeInterpreterUtils;
import mbdo.dt.utils.SysMLParserUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RuntimeInterpreter {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeInterpreter.class);

    public List<Transition> findEnabledGuardTransitions(
            StateMachine stateMachine,
            String sourceState,
            SysMLStructuralModel structuralModel,
            List<SysMLStructuralModel> allStructuralModels,
            Map<String, ?> observedValues
    ) {
        List<Transition> enabledTransitions = new ArrayList<>();

        for (Transition transition : stateMachine.transitions()) {
            if (!transition.sourceState().equals(sourceState)) {
                continue;
            }

            if (RuntimeInterpreterUtils.isRelativeTimeTransition(transition)) {
                continue;
            }

            if (canFireGuardTransition(transition, structuralModel, allStructuralModels, observedValues)) {
                enabledTransitions.add(transition);
            }
        }

        return enabledTransitions;
    }

    private boolean canFireGuardTransition(
            Transition transition,
            SysMLStructuralModel structuralModel,
            List<SysMLStructuralModel> allStructuralModels,
            Map<String, ?> observedValues
    ) {
        try {
            for (SysMLBehavioralModel.Condition condition : transition.conditions()) {
                if (!evaluateRuntimeCondition(condition, structuralModel, allStructuralModels, observedValues)) {
                    return false;
                }
            }

            return true;

        } catch (IllegalArgumentException e) {
            logger.warn("Could not evaluate transition '{}': {}",
                    transition.name(), e.getMessage());
            return false;
        }
    }

    private boolean evaluateRuntimeCondition(
            SysMLBehavioralModel.Condition condition,
            SysMLStructuralModel structuralModel,
            List<SysMLStructuralModel> allStructuralModels,
            Map<String, ?> observedValues
    ) {
        if (condition == null) {
            return false;
        }

        if (SysMLParserUtils.isFunctionCall(condition.rawExpression())) {
            Object result = evaluateCalcCall(
                    condition.rawExpression(), structuralModel, allStructuralModels, observedValues);
            return Boolean.parseBoolean(String.valueOf(result));
        }

        if (condition.operator() == SysMLBehavioralModel.Operator.UNKNOWN) {
            return false;
        }

        Object leftValue = RuntimeInterpreterUtils.resolveRuntimeValue(condition.leftOperand(), observedValues);
        Object rightValue = RuntimeInterpreterUtils.resolveRuntimeValue(condition.rightOperand(), observedValues);

        return RuntimeInterpreterUtils.evaluateRuntimeComparison(leftValue, condition.operator(), rightValue);
    }

    private boolean isTokenSequencerAvailabilityCheck(String calcName) {
        return calcName != null
                && ("isNextToken".equals(calcName) || calcName.endsWith("::isNextToken"));
    }

    private SysMLStructuralModel.CalcDefinition findCalcDefinition(
            String calcName,
            SysMLStructuralModel structuralModel,
            List<SysMLStructuralModel> allStructuralModels
    ) {
        if (structuralModel != null) {
            Optional<SysMLStructuralModel.CalcDefinition> local =
                    structuralModel.findCalcDefinition(calcName);

            if (local.isPresent()) {
                return local.get();
            }
        }

        for (SysMLStructuralModel other : allStructuralModels) {
            if (other == structuralModel) {
                continue;
            }

            Optional<SysMLStructuralModel.CalcDefinition> found =
                    other.findCalcDefinition(calcName);

            if (found.isPresent()) {
                return found.get();
            }
        }

        return null;
    }

    private Object evaluateCalcCall(
            String expression,
            SysMLStructuralModel structuralModel,
            List<SysMLStructuralModel> allStructuralModels,
            Map<String, ?> observedValues
    ) {
        String calcName = SysMLParserUtils.functionName(expression);

        if (calcName == null || structuralModel == null) {
            logger.warn("Could not evaluate calc expression '{}': no calc name or no structural model available.",
                    expression);
            return false;
        }

        if (isTokenSequencerAvailabilityCheck(calcName)) {
            return true;
        }

        SysMLStructuralModel.CalcDefinition calcDefinition =
                findCalcDefinition(calcName, structuralModel, allStructuralModels);

        if (calcDefinition == null || calcDefinition.returnExpression() == null) {
            logger.warn("Could not evaluate calc expression '{}': no calc definition named '{}' found in the active or any fallback model file.",
                    expression, calcName);
            return false;
        }

        List<String> arguments = SysMLParserUtils.functionArguments(expression);

        if (arguments.size() != calcDefinition.parameters().size()) {
            logger.warn("Could not evaluate calc expression '{}': expected {} argument(s) for '{}' but found {}.",
                    expression, calcDefinition.parameters().size(), calcName, arguments.size());
            return false;
        }

        Map<String, Object> localValues = new HashMap<>();
        if (observedValues != null) {
            localValues.putAll(observedValues);
        }

        for (int i = 0; i < arguments.size(); i++) {
            String parameterName = calcDefinition.parameters().get(i).name();

            Object argumentValue =
                    RuntimeInterpreterUtils.resolveRuntimeValue(
                            arguments.get(i),
                            observedValues
                    );

            localValues.put(parameterName, argumentValue);
        }

        return evaluateCalcReturnExpression(
                calcDefinition.returnExpression(),
                localValues
        );
    }

    private boolean evaluateCalcReturnExpression(String expression, Map<String, ?> values) {
        for (String clause : SysMLParserUtils.splitTopLevelAnd(expression)) {
            SysMLBehavioralModel.Condition condition =
                    RuntimeInterpreterUtils.parseRuntimeCondition(clause);

            if (condition.operator() == SysMLBehavioralModel.Operator.UNKNOWN) {
                return false;
            }

            Object leftValue =
                    RuntimeInterpreterUtils.resolveRuntimeArithmetic(
                            condition.leftOperand(),
                            values
                    );

            Object rightValue =
                    RuntimeInterpreterUtils.resolveRuntimeArithmetic(
                            condition.rightOperand(),
                            values
                    );

            if (!RuntimeInterpreterUtils.evaluateRuntimeComparison(
                    leftValue,
                    condition.operator(),
                    rightValue
            )) {
                return false;
            }
        }

        return true;
    }
}
