package mbdo.dt.services.impl;

import mbdo.dt.domain.DataRequest;
import mbdo.dt.services.RuntimeContext;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RuntimeContextService implements RuntimeContext {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeContextService.class);

    private final Map<String, Object> latestValues = new ConcurrentHashMap<>();

    private volatile String activeStateMachineId;
    private volatile String activeStateMachineName;
    private volatile String currentState;

    @Override
    public void onDataRequest(DataRequest request) {
        if (request == null) {
            return;
        }

        if (request.attributeName() != null && !request.attributeName().isBlank()) {
            if (request.value() != null) {
                latestValues.put(request.attributeName(), request.value());
            } else {
                logger.warn(
                        "Ignoring runtime value for '{}' because the received value is null.",
                        request.attributeName()
                );
            }
        }

        if (request.timestamp() != null) {
            latestValues.put("timestamp", request.timestamp());
        }
    }

    @Override
    public Map<String, Object> snapshot() {
        return new ConcurrentHashMap<>(latestValues);
    }

    @Override
    public void updateResolvedRuntimeState(
            String activeStateMachineId,
            String activeStateMachineName,
            String currentState
    ) {
        if (activeStateMachineId != null && !activeStateMachineId.isBlank()) {
            this.activeStateMachineId = activeStateMachineId;
        }

        if (activeStateMachineName != null && !activeStateMachineName.isBlank()) {
            this.activeStateMachineName = activeStateMachineName;
        }

        if (currentState != null && !currentState.isBlank()) {
            this.currentState = currentState;
        }
    }

    @Override
    public String getActiveStateMachineId() {
        return activeStateMachineId;
    }

    @Override
    public String getActiveStateMachineName() {
        return activeStateMachineName;
    }

    @Override
    public String getCurrentState() {
        return currentState;
    }
}