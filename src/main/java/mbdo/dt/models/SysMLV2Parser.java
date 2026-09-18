package mbdo.dt.models;

import mbdo.dt.models.SysMLBehavioralModel.StateMachine;
import mbdo.dt.models.SysMLStructuralModel.ActionDefinition;
import mbdo.dt.models.SysMLStructuralModel.AttributeDefinition;
import mbdo.dt.models.SysMLStructuralModel.CalcDefinition;
import mbdo.dt.models.SysMLStructuralModel.ConstraintDefinition;
import mbdo.dt.models.SysMLStructuralModel.ConstraintUsage;
import mbdo.dt.models.SysMLStructuralModel.EnumDefinition;
import mbdo.dt.models.SysMLStructuralModel.MeasurementSchema;
import mbdo.dt.models.SysMLStructuralModel.PartDefinition;
import mbdo.dt.utils.SysMLParserUtils;
import mbdo.dt.utils.SysMLParserUtils.Block;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for parsing a SysML v2 model file into {@link ParsedSysMLModel}. Cleans the raw
 * text, then orchestrates the structural and behavioral extractors that do the actual parsing:
 * {@link StructuralDefinitionExtractor} and {@link ConstraintExtractor} for the structural
 * model, {@link BehavioralElementExtractor} for each state machine's behavioral model, and
 * {@link DatabaseSchemaDeriver} to derive the InfluxDB schema from the parsed structure.
 */
@Component
public class SysMLV2Parser {

    private final DatabaseSchemaDeriver databaseSchemaDeriver;
    private final StructuralDefinitionExtractor structuralDefinitionExtractor;
    private final ConstraintExtractor constraintExtractor;
    private final BehavioralElementExtractor behavioralElementExtractor;

    @Autowired
    public SysMLV2Parser(
            DatabaseSchemaDeriver databaseSchemaDeriver,
            StructuralDefinitionExtractor structuralDefinitionExtractor,
            ConstraintExtractor constraintExtractor,
            BehavioralElementExtractor behavioralElementExtractor
    ) {
        this.databaseSchemaDeriver = databaseSchemaDeriver;
        this.structuralDefinitionExtractor = structuralDefinitionExtractor;
        this.constraintExtractor = constraintExtractor;
        this.behavioralElementExtractor = behavioralElementExtractor;
    }

    public ParsedSysMLModel loadModel(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("SysML model resource cannot be null.");
        }

        if (!resource.exists()) {
            throw new IllegalArgumentException("SysML model resource does not exist: " + resource.getDescription()
            );
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SysML model resource: " + resource.getDescription(), e);
        }
    }

    public ParsedSysMLModel parse(String content) {
        String cleanedContent = SysMLParserUtils.cleanContent(content);

        SysMLStructuralModel structuralModel = parseStructuralModel(cleanedContent);
        SysMLBehavioralModel behavioralModel = parseBehavioralModel(cleanedContent);

        return new ParsedSysMLModel(structuralModel, behavioralModel);
    }

    private SysMLStructuralModel parseStructuralModel(String content) {
        List<EnumDefinition> enumDefinitions = structuralDefinitionExtractor.extractEnumDefinitions(content);
        List<AttributeDefinition> attributeDefinitions = structuralDefinitionExtractor.extractAttributeDefinitions(content);
        List<ActionDefinition> actionDefinitions = structuralDefinitionExtractor.extractActionDefinitions(content);
        List<ConstraintDefinition> constraintDefinitions = constraintExtractor.extractConstraintDefinitions(content);
        List<ConstraintUsage> constraintUsages = constraintExtractor.extractConstraintUsages(content);
        List<PartDefinition> partDefinitions = structuralDefinitionExtractor.extractPartDefinitions(content);
        List<CalcDefinition> calcDefinitions = structuralDefinitionExtractor.extractCalcDefinitions(content);
        List<MeasurementSchema> databaseSchema = databaseSchemaDeriver.derive(
                partDefinitions,
                enumDefinitions,
                attributeDefinitions
        );

        return new SysMLStructuralModel(
                structuralDefinitionExtractor.extractRootPackageName(content),
                enumDefinitions,
                attributeDefinitions,
                actionDefinitions,
                calcDefinitions,
                constraintDefinitions,
                constraintUsages,
                partDefinitions,
                databaseSchema
        );
    }

    private SysMLBehavioralModel parseBehavioralModel(String content) {
        List<StateMachine> stateMachines = new ArrayList<>();

        for (Block block : SysMLParserUtils.extractBlocks(content, "state def")) {
            stateMachines.add(new StateMachine(
                    block.name(),
                    behavioralElementExtractor.extractInitialState(block.body()),
                    behavioralElementExtractor.extractStates(block.body()),
                    behavioralElementExtractor.extractTransitions(block.body())
            ));
        }

        return new SysMLBehavioralModel(stateMachines);
    }

    public record ParsedSysMLModel(
            SysMLStructuralModel structuralModel,
            SysMLBehavioralModel behavioralModel
    ) {
    }
}
