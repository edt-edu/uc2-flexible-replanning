package mbdo.dt.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

public final class YamlUtils {

    private YamlUtils() {
    }

    public static <T> T convertYamlToObject(
            ApplicationContext applicationContext,
            String yamlFilePath,
            Class<T> objectClass
    ) {
        Resource resource = applicationContext.getResource(yamlFilePath);

        try (InputStream inputStream = resource.getInputStream()) {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            JsonNode root = yamlMapper.readTree(inputStream);

            Iterator<Map.Entry<String, JsonNode>> rootFields = root.fields();

            if (!rootFields.hasNext()) {
                throw new IllegalStateException(
                        "YAML file has no root key to unwrap: " + yamlFilePath
                );
            }

            JsonNode firstValue = rootFields.next().getValue();

            return yamlMapper.treeToValue(firstValue, objectClass);
        } catch (IOException e) {
            throw new RuntimeException("Error converting YAML file to object: " + yamlFilePath, e);
        }
    }
}