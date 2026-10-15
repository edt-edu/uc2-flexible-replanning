package mbdo.dt.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.events.WriteErrorEvent;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import jakarta.annotation.PreDestroy;
import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.replanning.ReplanningPlan;
import mbdo.dt.utils.TimestampParsingUtils;
import mbdo.dt.utils.TypeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DigitalShadow {

    private static final Logger logger = LoggerFactory.getLogger(DigitalShadow.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String REPLANNING_PLAN = "replanningPlan";
    private static final String RECOVERY_PLAN = "recoveryPlan";

    private final InfluxDBClient influxDBClient;
    private final String bucket;
    private final String org;
    /**
     * Batched, non-blocking writes for the measurements: a moving machine publishes hundreds of them per second,
     * more than one HTTP round trip each can keep up with.
     */
    private final WriteApi measurementWriteApi;

    public DigitalShadow(
            InfluxDBClient influxDBClient,
            @Value("${influx.bucket}") String bucket,
            @Value("${influx.org}") String org
    ) {
        this.influxDBClient = influxDBClient;
        this.bucket = bucket;
        this.org = org;
        this.measurementWriteApi = influxDBClient.makeWriteApi(
                WriteOptions.builder().batchSize(1000).flushInterval(200).build());
        this.measurementWriteApi.listenEvents(WriteErrorEvent.class, event ->
                logger.warn("failed to write measurements to InfluxDB: {}", event.getThrowable().getMessage()));
    }

    @PreDestroy
    void close() {
        measurementWriteApi.close();
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
            measurementWriteApi.writePoint(bucket, org, point);
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
    /**
     * Records the anomaly; recorded again with its replanning (same tags and time), InfluxDB adds the replanning to
     * the same record.
     */
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
            if (anomalyContext.replanningPlan() != null) {
                point.addField(REPLANNING_PLAN, OBJECT_MAPPER.writeValueAsString(anomalyContext.replanningPlan()));
            }

            influxDBClient.getWriteApiBlocking().writePoint(bucket, org, point);
        } catch (JsonProcessingException | RuntimeException e) {
            logger.warn("failed to record anomaly '{}' to InfluxDB: {}",
                    anomalyContext.anomalyId(), e.getMessage());
        }
    }

    /**
     * Records the operator's correction of the anomaly; recorded again with the recovery of the anomaly (same time),
     * InfluxDB adds the recovery to the same record.
     */
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
            if (originalContext.recoveryPlan() != null) {
                point.addField(RECOVERY_PLAN, OBJECT_MAPPER.writeValueAsString(originalContext.recoveryPlan()));
            }

            influxDBClient.getWriteApiBlocking().writePoint(bucket, org, point);
        } catch (JsonProcessingException | RuntimeException e) {
            logger.warn("failed to record recovery for anomaly '{}' to InfluxDB: {}",
                    originalContext.anomalyId(), e.getMessage());
        }
    }

    /** A recorded anomaly (with its replanning and recovery), and its correction by the operator (null if none). */
    public record IncidentRecord(AnomalyContext anomaly, Instant recoveredAt, String comment) {
    }

    /**
     * The anomalies recorded over the last {@code lookback}, oldest first, with their corrections: the pairs of
     * anomaly_events and anomaly_recoveries records of the same anomalyId.
     *
     * @throws RuntimeException if InfluxDB can't be queried
     */
    public List<IncidentRecord> loadIncidents(Duration lookback) {
        String range = "from(bucket: \"" + bucket + "\") |> range(start: -" + lookback.toSeconds() + "s)";
        String pivot = " |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")";

        Map<String, FluxRecord> recoveries = new HashMap<>();
        for (FluxRecord row : query(range + " |> filter(fn: (r) => r._measurement == \"anomaly_recoveries\")" + pivot)) {
            recoveries.put(string(row, "anomalyId"), row);
        }

        List<IncidentRecord> incidents = new ArrayList<>();
        for (FluxRecord row : query(range + " |> filter(fn: (r) => r._measurement == \"anomaly_events\")" + pivot
                + " |> group() |> sort(columns: [\"_time\"])")) {
            String anomalyId = string(row, "anomalyId");
            FluxRecord recovery = recoveries.get(anomalyId);
            incidents.add(new IncidentRecord(
                    new AnomalyContext(anomalyId, row.getTime(), string(row, "nominalPath"),
                            string(row, "affectedStep"), string(row, "affectedComponent"),
                            string(row, "currentWorkpieceLocation"), string(row, "currentState"),
                            string(row, "message"), plan(row, REPLANNING_PLAN),
                            recovery == null ? null : plan(recovery, RECOVERY_PLAN)),
                    recovery == null ? null : recovery.getTime(),
                    recovery == null ? null : string(recovery, "comment")));
        }
        return incidents;
    }

    private static ReplanningPlan plan(FluxRecord record, String column) {
        String json = string(record, column);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, ReplanningPlan.class);
        } catch (JsonProcessingException e) {
            logger.warn("unreadable {} of anomaly '{}': {}", column, string(record, "anomalyId"), e.getMessage());
            return null;
        }
    }

    private List<FluxRecord> query(String flux) {
        List<FluxRecord> records = new ArrayList<>();
        for (FluxTable table : influxDBClient.getQueryApi().query(flux, org)) {
            records.addAll(table.getRecords());
        }
        return records;
    }

    private static String string(FluxRecord record, String column) {
        Object value = record.getValueByKey(column);
        return value == null ? null : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}