package mbdo.dt.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.utils.TimestampParsingUtils;
import mbdo.dt.utils.TypeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DigitalShadow {

    private static final Logger logger = LoggerFactory.getLogger(DigitalShadow.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InfluxDBClient influxDBClient;
    private final String bucket;
    private final String org;

    public DigitalShadow(
            InfluxDBClient influxDBClient,
            @Value("${influx.bucket}") String bucket,
            @Value("${influx.org}") String org
    ) {
        this.influxDBClient = influxDBClient;
        this.bucket = bucket;
        this.org = org;
    }

    public void processAndStoreMessage(
            String measurement,
            String topic,
            String mqttMessage,
            boolean extractTimestampFromJsonField,
            boolean extractValueFromJsonField
    ) {
        Point point;

        try {
            point = Point.measurement(measurement)
                    .addTag("topic", topic)
                    .addField("message", mqttMessage)
                    .time(System.currentTimeMillis(), WritePrecision.MS);

            JsonNode jsonNode = OBJECT_MAPPER.readTree(mqttMessage);

            if (jsonNode.get("timestamp") != null) {
                point.addField("timestamp", jsonNode.get("timestamp").asText());

                if (extractTimestampFromJsonField) {
                    JsonNode timestampNode = jsonNode.get("timestamp");

                    Long parsedMillis = timestampNode.isNumber()
                            ? timestampNode.asLong()
                            : TimestampParsingUtils.parseEpochMillisOrNull(timestampNode.asText());

                    if (parsedMillis != null) {
                        point.time(parsedMillis, WritePrecision.MS);
                    } else {
                        logger.warn(
                                "Could not parse timestamp '{}' for measurement '{}' (topic '{}'). Using wall clock.",
                                timestampNode.asText(), measurement, topic);
                        point.time(System.currentTimeMillis(), WritePrecision.MS);
                    }
                }
            }

            if (jsonNode.get("value") != null) {
                String value = jsonNode.get("value").asText();

                if (extractValueFromJsonField) {
                    String fieldName = extractFieldNameFromTopic(topic);
                    addDetectedField(point, fieldName, value);
                }
            }
        } catch (JsonProcessingException e) {
            logger.error("failed to parse mqtt message for measurement '{}', topic '{}': {}",
                    measurement, topic, e.getMessage());
            return;
        }

        try {
            influxDBClient.getWriteApiBlocking().writePoint(bucket, org, point);
        } catch (RuntimeException e) {
            logger.warn("failed to write measurement '{}' (topic '{}') to InfluxDB: {}",
                    measurement, topic, e.getMessage());
        }
    }

    private void addDetectedField(Point point, String fieldName, String value) {
        if (TypeDetector.isBoolean(value)) {
            point.addField(fieldName, Boolean.parseBoolean(value));
        } else if (TypeDetector.isInteger(value)) {
            point.addField(fieldName, Long.parseLong(value));
        } else if (TypeDetector.isFloat(value)) {
            point.addField(fieldName, Double.parseDouble(value));
        } else {
            point.addField(fieldName, value);
        }
    }

    private String extractFieldNameFromTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return "value";
        }

        String[] parts = topic.split("/");
        return parts[parts.length - 1];
    }
    public boolean isAvailable() {
        try {
            return Boolean.TRUE.equals(influxDBClient.ping());
        } catch (Exception e) {
            logger.warn("Could not connect to InfluxDB.", e);
            return false;
        }
    }
    public void recordAnomaly(AnomalyContext anomalyContext) {
        if (anomalyContext == null) {
            return;
        }

        try {
            Point point = Point.measurement("anomaly_events")
                    .addTag("anomalyId", anomalyContext.anomalyId())
                    .addTag("affectedComponent", nullToEmpty(anomalyContext.affectedComponent()))
                    .addTag("nominalPath", nullToEmpty(anomalyContext.nominalPath()))
                    .addField("affectedStep", nullToEmpty(anomalyContext.affectedStep()))
                    .addField("currentWorkpieceLocation", nullToEmpty(anomalyContext.currentWorkpieceLocation()))
                    .addField("currentState", nullToEmpty(anomalyContext.currentState()))
                    .addField("message", nullToEmpty(anomalyContext.message()))
                    .time(anomalyContext.detectionTimestamp().toEpochMilli(), WritePrecision.MS);

            influxDBClient.getWriteApiBlocking().writePoint(bucket, org, point);
        } catch (RuntimeException e) {
            logger.warn("failed to record anomaly '{}' to InfluxDB: {}",
                    anomalyContext.anomalyId(), e.getMessage());
        }
    }

    public void recordRecovery(AnomalyContext originalContext, String comment, Instant recoveryTimestamp) {
        if (originalContext == null) {
            return;
        }

        try {
            Point point = Point.measurement("anomaly_recoveries")
                    .addTag("anomalyId", originalContext.anomalyId())
                    .addField("comment", nullToEmpty(comment))
                    .addField("disruptionTimestamp", originalContext.detectionTimestamp().toEpochMilli())
                    .time(recoveryTimestamp.toEpochMilli(), WritePrecision.MS);

            influxDBClient.getWriteApiBlocking().writePoint(bucket, org, point);
        } catch (RuntimeException e) {
            logger.warn("failed to record recovery for anomaly '{}' to InfluxDB: {}",
                    originalContext.anomalyId(), e.getMessage());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}