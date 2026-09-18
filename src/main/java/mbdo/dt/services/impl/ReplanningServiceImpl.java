package mbdo.dt.services.impl;

import mbdo.dt.domain.DataRequest;
import mbdo.dt.services.AnomalyDetection.AnomalyEvaluationResult;
import mbdo.dt.services.ReplanningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ReplanningServiceImpl implements ReplanningService {

    private static final Logger logger = LoggerFactory.getLogger(ReplanningServiceImpl.class);

    public ReplanningServiceImpl() {
    }

    @Override
    public void handleAnomaly(
            DataRequest triggeringRequest,
            AnomalyEvaluationResult anomalyResult,
            Map<String, Object> latestRuntimeValues
    ) {
        //TODO: to be implemented

    }
    private String selectMission(Map<String, Object> values) {
        //TODO: to be implemented

        return null;
    }



}