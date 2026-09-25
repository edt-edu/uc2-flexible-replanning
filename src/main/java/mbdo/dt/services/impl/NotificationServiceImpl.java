package mbdo.dt.services.impl;

import mbdo.dt.domain.AnomalyContext;
import mbdo.dt.incident.ReplanningIncidentRegistryService;
import mbdo.dt.incident.ReplanningIncidentSseService;
import mbdo.dt.services.NotificationService;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final ReplanningIncidentRegistryService registryService;
    private final ReplanningIncidentSseService sseService;

    public NotificationServiceImpl(
            ReplanningIncidentRegistryService registryService,
            ReplanningIncidentSseService sseService
    ) {
        this.registryService = registryService;
        this.sseService = sseService;
    }

    @Override
    public void notifyAnomaly(AnomalyContext context) {
        if (context == null) {
            return;
        }

        sseService.broadcastOpened(registryService.openIncident(context));
    }
}
