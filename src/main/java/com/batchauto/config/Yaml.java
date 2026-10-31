package com.batchauto.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.batchauto.errors.BatchAutoException.ConfigException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Strict YAML binding: snake_case keys, unknown keys rejected, decimals kept exact. */
public final class Yaml {

    public static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);

    private Yaml() { }

    public static JsonNode readTree(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (NoSuchFileException e) {
            throw new ConfigException("missing file: " + file.getFileName());
        } catch (IOException e) {
            throw new ConfigException(file.getFileName() + ": " + firstLine(e.getMessage()), e);
        }
    }

    public static <T> T read(Path file, Class<T> type) {
        return convert(readTree(file), type, file.getFileName().toString());
    }

    public static <T> T convert(JsonNode node, Class<T> type, String where) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigException(where + ": " + firstLine(e.getMessage()), e);
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "invalid";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
