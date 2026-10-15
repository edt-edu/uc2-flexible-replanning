import { Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { ReplanningIncident } from '../models/replanning-incident.model';
import { ReplanningIncidentService } from '../services/replanning-incident.service';

@Injectable({
    providedIn: 'root'
})
export class ReplanningIncidentStore {
    private readonly historyState = signal<ReplanningIncident[]>([]);
    private readonly currentIncidentState = signal<ReplanningIncident | null>(null);

    readonly history = this.historyState.asReadonly();
    readonly currentIncident = this.currentIncidentState.asReadonly();

    private subscriptions: Subscription[] = [];

    constructor(private readonly replanningIncidentService: ReplanningIncidentService) { }

    connect(): void {
        if (this.subscriptions.length) {
            return;
        }

        const { opened, updated, resolved } = this.replanningIncidentService.connectToIncidentStream();

        this.subscriptions.push(
            opened.subscribe(incident => this.currentIncidentState.set(incident)),
            updated.subscribe(incident => {
                if (this.currentIncidentState()?.id === incident.id) {
                    this.currentIncidentState.set(incident);
                } else {
                    // a resolved incident: its recovery (back to the nominal missions) progresses
                    this.historyState.update(history =>
                        history.map(past => past.id === incident.id ? incident : past));
                }
            }),
            resolved.subscribe(incident => {
                this.currentIncidentState.set(null);
                this.historyState.update(history => [incident, ...history]);
            }),
            this.replanningIncidentService.getCurrentIncident().subscribe({
                next: incident => this.currentIncidentState.set(incident),
                error: () => this.currentIncidentState.set(null)
            }),
            this.replanningIncidentService.getHistory().subscribe({
                next: history => this.historyState.set(history),
                error: () => this.historyState.set([])
            })
        );
    }

    disconnect(): void {
        this.subscriptions.forEach(subscription => subscription.unsubscribe());
        this.subscriptions = [];
        this.replanningIncidentService.disconnectFromIncidentStream();
    }

    resolveCurrentIncident(comment: string): void {
        const currentIncident = this.currentIncidentState();
        if (!currentIncident) {
            return;
        }

        this.replanningIncidentService.resolveIncident(currentIncident.id, comment).subscribe({
            error: error => console.error('[ReplanningIncidentStore] Failed to resolve incident:', error)
        });
        // Local state is updated from the resulting "incident-resolved" SSE event so every
        // connected client (both roles) converges on the same server-authoritative state.
    }
}
