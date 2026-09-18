package mbdo.dt.domain;

public record DataRequest(
        String topic,
        String modelName,
        String attributeName,
        Object value,
        Long timestamp
) {
}