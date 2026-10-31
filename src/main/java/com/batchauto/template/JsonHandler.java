package com.batchauto.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.batchauto.config.JobConfig;
import com.batchauto.errors.BatchAutoException.ConfigException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import freemarker.core.PlainTextOutputFormat;

/**
 * JSON: every value reaching the template is already a JSON literal produced by Jackson
 * (strings escaped, BigDecimal written exactly, null as null), so templates can't break
 * the document. Validation: JSON Schema 2020-12 with format assertions on.
 */
public final class JsonHandler extends FormatHandler {

    static final ObjectMapper LITERALS = JsonMapper.builder()
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();
    static final ObjectMapper EXACT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    public JsonHandler(JobConfig job, Path jobDir) {
        super(job, jobDir, PlainTextOutputFormat.INSTANCE);
    }

    @Override
    public String ext() { return "json"; }

    static String literal(Object v) {
        try {
            return LITERALS.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot encode " + v, e);
        }
    }

    @Override
    protected Map<String, Object> encodeRecord(Map<String, Object> record) {
        Map<String, Object> out = new HashMap<>();
        record.forEach((k, v) -> out.put(k, literal(v)));
        return out;
    }

    @Override
    protected Object encodeContext(Object value) {
        return literal(value);
    }

    private JsonSchema schema() {
        try {
            JsonNode schemaNode = EXACT.readTree(Files.readString(jobDir.resolve(job.file().schemaFile())));
            SchemaValidatorsConfig cfg = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode, cfg);
        } catch (IOException | RuntimeException e) {
            throw new ConfigException(job.jobId() + ": invalid JSON Schema: " + e.getMessage(), e);
        }
    }

    @Override
    public FileValidationResult validate(byte[] content) {
        JsonSchema schema = schema();
        String schemaName = job.file().schemaFile();
        JsonNode doc;
        try {
            doc = EXACT.readTree(new String(content, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            return new FileValidationResult(false, List.of("malformed JSON: " + e.getOriginalMessage()), null, schemaName);
        }
        if (doc == null || doc.isMissingNode()) {
            return new FileValidationResult(false, List.of("malformed JSON: empty document"), null, schemaName);
        }
        Integer count = null;
        if (job.file().recordsPath() != null) {
            JsonNode n = doc;
            for (String part : job.file().recordsPath().split("\\.")) {
                n = n.path(part);
            }
            count = n.isArray() ? n.size() : null;
        }
        Set<ValidationMessage> messages = schema.validate(doc);
        List<String> errors = new ArrayList<>(messages.stream().map(ValidationMessage::getMessage).sorted(Comparator.naturalOrder()).toList());
        return new FileValidationResult(errors.isEmpty(), errors, count, schemaName);
    }
}
