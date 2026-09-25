export type ReplanningIncidentStatus = 'OPEN' | 'RESOLVED';

/**
 * Mirrors the backend's mbdo.dt.incident.ReplanningIncidentResponse. Unlike cep-service's
 * mocked version, this does not extend a Rule/Incident model - there is no rule engine here,
 * incidents originate from the digital twin's own anomaly detection.
 */
export interface ReplanningIncident {
    id: number;
    machineId: string;
    message: string;
    requiredAction: string;
    status: ReplanningIncidentStatus;
    startTime: string;
    createdAt: string;
    resolvedAt?: string;
    operatorComment?: string;
}
