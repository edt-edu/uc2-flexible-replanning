package mbdo.dt.services;

import mbdo.dt.domain.DataRequest;
import java.util.Map;

public interface RuntimeContext {

    void onDataRequest(DataRequest request);

    Map<String, Object> snapshot();

    void updateResolvedRuntimeState(
            String activeStateMachineId,
            String activeStateMachineName,
            String currentState
    );

    String getActiveStateMachineId();

    String getActiveStateMachineName();

    String getCurrentState();
}