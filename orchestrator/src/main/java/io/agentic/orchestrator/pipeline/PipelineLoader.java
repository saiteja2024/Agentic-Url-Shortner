package io.agentic.orchestrator.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.lang.Nullable;

/** Reads and validates a pipeline definition (ORCH-001, ORCH-002, ORCH-003). */
public final class PipelineLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private PipelineLoader() {}

    /** @throws PipelineDefinitionException if the YAML is missing, malformed or describes an invalid graph */
    public static PipelineDefinition load(@Nullable InputStream yaml) {
        if (yaml == null) {
            throw new PipelineDefinitionException(List.of("pipeline definition not found"));
        }
        PipelineDefinition definition;
        try {
            definition = YAML.readValue(yaml, PipelineDefinition.class);
        } catch (UnrecognizedPropertyException e) {
            throw new PipelineDefinitionException("unknown key '" + e.getPropertyName() + "' at " + location(e), e);
        } catch (JsonProcessingException e) {
            throw new PipelineDefinitionException("not valid pipeline YAML: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new PipelineDefinitionException("pipeline definition could not be read: " + e.getMessage(), e);
        }
        return PipelineValidator.validate(definition);
    }

    private static String location(JsonMappingException e) {
        return e.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining("."))
                .replace(".[", "[");
    }
}
