package mbdo.dt.models;

import mbdo.dt.models.SysMLStructuralModel.Attribute;
import mbdo.dt.models.SysMLStructuralModel.AttributeDefinition;
import mbdo.dt.models.SysMLStructuralModel.EnumDefinition;
import mbdo.dt.models.SysMLStructuralModel.FieldSchema;
import mbdo.dt.models.SysMLStructuralModel.FieldType;
import mbdo.dt.models.SysMLStructuralModel.MeasurementSchema;
import mbdo.dt.models.SysMLStructuralModel.PartDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an already-parsed structural model into the InfluxDB measurement/field schema used to
 * store observed values - a separate concern from parsing SysML text itself. Stateless: every
 * call is given the definitions it needs to work from.
 */
@Component
public class DatabaseSchemaDeriver {

    public List<MeasurementSchema> derive(
            List<PartDefinition> partDefinitions,
            List<EnumDefinition> enumDefinitions,
            List<AttributeDefinition> attributeDefinitions
    ) {
        Set<String> enumNames = new HashSet<>();

        for (EnumDefinition enumDefinition : enumDefinitions) {
            enumNames.add(enumDefinition.name());
        }

        Map<String, AttributeDefinition> attributeDefinitionsByName = new HashMap<>();

        for (AttributeDefinition attributeDefinition : attributeDefinitions) {
            attributeDefinitionsByName.put(attributeDefinition.name(), attributeDefinition);
        }

        List<MeasurementSchema> measurements = new ArrayList<>();

        for (PartDefinition partDefinition : partDefinitions) {
            if (partDefinition.attributes().isEmpty()) {
                continue;
            }

            List<FieldSchema> fields = new ArrayList<>();

            for (Attribute attribute : partDefinition.attributes()) {
                addFieldsForAttribute(
                        fields,
                        attribute.name(),
                        attribute,
                        enumNames,
                        attributeDefinitionsByName
                );
            }

            measurements.add(new MeasurementSchema(
                    partDefinition.name(),
                    partDefinition.name(),
                    fields
            ));
        }

        return measurements;
    }

    private void addFieldsForAttribute(
            List<FieldSchema> fields,
            String fieldName,
            Attribute attribute,
            Set<String> enumNames,
            Map<String, AttributeDefinition> attributeDefinitionsByName
    ) {
        AttributeDefinition nestedAttributeDefinition =
                attributeDefinitionsByName.get(attribute.type());

        if (nestedAttributeDefinition != null) {
            for (Attribute nestedAttribute : nestedAttributeDefinition.attributes()) {
                addFieldsForAttribute(
                        fields,
                        fieldName + "." + nestedAttribute.name(),
                        nestedAttribute,
                        enumNames,
                        attributeDefinitionsByName
                );
            }

            return;
        }

        fields.add(new FieldSchema(
                fieldName,
                attribute.type(),
                toFieldType(attribute.type(), enumNames)
        ));
    }

    private FieldType toFieldType(String sysmlType, Set<String> enumNames) {
        if (sysmlType == null) {
            return FieldType.STRING;
        }

        String type = sysmlType.trim();

        if (enumNames.contains(type)) {
            return FieldType.STRING;
        }

        return switch (type) {
            case "Boolean" -> FieldType.BOOLEAN;
            case "Integer" -> FieldType.INTEGER;
            case "Real" -> FieldType.FLOAT;
            case "String" -> FieldType.STRING;
            default -> FieldType.STRING;
        };
    }
}
