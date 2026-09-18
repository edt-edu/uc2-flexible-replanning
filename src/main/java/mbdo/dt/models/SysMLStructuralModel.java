package mbdo.dt.models;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SysMLStructuralModel(
        String rootPackageName,
        List<EnumDefinition> enumDefinitions,
        List<AttributeDefinition> attributeDefinitions,
        List<ActionDefinition> actionDefinitions,
        List<CalcDefinition> calcDefinitions,
        List<ConstraintDefinition> constraintDefinitions,
        List<ConstraintUsage> constraintUsages,
        List<PartDefinition> partDefinitions,
        List<MeasurementSchema> databaseSchema
) {

    public List<ConstraintUsage> findConstraintUsagesByType(String type) {
        return constraintUsages.stream()
                .filter(constraintUsage -> constraintUsage.type().equals(type)
                        || constraintUsage.type().endsWith("::" + type))
                .toList();
    }

    public Optional<CalcDefinition> findCalcDefinition(String name) {
        return calcDefinitions.stream()
                .filter(calcDefinition -> namesMatch(calcDefinition.name(), name))
                .findFirst();
    }

    private boolean namesMatch(String candidate, String expected) {
        if (candidate == null || expected == null) {
            return false;
        }

        return candidate.equals(expected)
                || candidate.endsWith("::" + expected)
                || expected.endsWith("::" + candidate);
    }

    public record EnumDefinition(
            String name,
            List<String> literals
    ) {
    }

    public record AttributeDefinition(
            String name,
            List<Attribute> attributes
    ) {
    }

    public record ActionDefinition(
            String name,
            List<ActionParameter> parameters,
            List<String> bodySteps
    ) {
    }

    public record ActionParameter(
            String direction,
            String name,
            String type,
            String defaultValue
    ) {
    }

    public record ConstraintDefinition(
            String name,
            List<ConstraintParameter> parameters
    ) {
    }

    public record ConstraintParameter(
            String direction,
            String name,
            String type
    ) {
    }

    public record ConstraintUsage(
            String name,
            String type,
            Map<String, String> inputValues
    ) {
    }

    public record PartDefinition(
            String name,
            List<Attribute> attributes,
            List<PerformedAction> performedActions,
            List<StateUsage> stateUsages
    ) {
    }

    public record Attribute(
            String name,
            String type,
            String defaultValue
    ) {
    }

    public record PerformedAction(
            String name,
            String type
    ) {
    }

    public record StateUsage(
            String name,
            String type
    ) {
    }

    public record MeasurementSchema(
            String measurementName,
            String sourcePartDefinition,
            List<FieldSchema> fields
    ) {
    }

    public record FieldSchema(
            String fieldName,
            String sysmlType,
            FieldType fieldType
    ) {
    }

    public enum FieldType {
        BOOLEAN,
        INTEGER,
        FLOAT,
        STRING
    }

    public record CalcDefinition(
            String name,
            List<CalcParameter> parameters,
            String returnType,
            String returnExpression
    ) {
    }

    public record CalcParameter(
            String direction,
            String name,
            String type
    ) {
    }
}