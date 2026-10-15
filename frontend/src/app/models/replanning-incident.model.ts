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
    replanningPlan?: ReplanningPlan | null;
}

/** Mirrors the backend's mbdo.dt.replanning.ReplanningPlan: what the digital twin does to the SCADA, at once. */
export type ReplanningPlanStatus = 'WAITING' | 'PLANNED' | 'EXECUTING' | 'EXECUTED' | 'FAILED';

export interface ReplanningPlan {
    strategy: string;
    description: string;
    anomalyId: string | null;
    status: ReplanningPlanStatus;
    steps: ReplanningPlanStep[];
    problems: string[];
    message: string | null;
}

export interface ReplanningPlanStep {
    kind: 'STOP_MISSION' | 'MIGRATE';
    machine: string;
    rule: string | null;
    fromMission: string | null;
    fromState: string | null;
    toMission: string | null;
    toState: string | null;
    status: 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED';
    detail: string;
}
