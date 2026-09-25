import { Injectable, NgZone } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';

const API_URL = '/api';
import { ReplanningIncident } from '../models/replanning-incident.model';

@Injectable({
    providedIn: 'root'
})
export class ReplanningIncidentService {
    private eventSource: EventSource | null = null;
    private readonly incidentOpenedSubject = new Subject<ReplanningIncident>();
    private readonly incidentResolvedSubject = new Subject<ReplanningIncident>();

    constructor(
        private readonly http: HttpClient,
        private readonly ngZone: NgZone
    ) { }

    connectToIncidentStream(): { opened: Observable<ReplanningIncident>; resolved: Observable<ReplanningIncident> } {
        if (this.eventSource) {
            this.eventSource.close();
        }

        const url = `${API_URL}/replanning-incidents/stream`;
        this.eventSource = new EventSource(url);

        this.eventSource.addEventListener('incident-opened', (event: MessageEvent) => {
            this.ngZone.run(() => {
                try {
                    this.incidentOpenedSubject.next(JSON.parse(event.data));
                } catch (error) {
                    console.error('[ReplanningIncidentService] Error parsing incident-opened event:', error);
                }
            });
        });

        this.eventSource.addEventListener('incident-resolved', (event: MessageEvent) => {
            this.ngZone.run(() => {
                try {
                    this.incidentResolvedSubject.next(JSON.parse(event.data));
                } catch (error) {
                    console.error('[ReplanningIncidentService] Error parsing incident-resolved event:', error);
                }
            });
        });

        this.eventSource.onerror = (error) => {
            console.error('[ReplanningIncidentService] SSE connection error:', error);

            if (this.eventSource) {
                this.eventSource.close();
                this.eventSource = null;
            }

            setTimeout(() => this.connectToIncidentStream(), 5000);
        };

        return {
            opened: this.incidentOpenedSubject.asObservable(),
            resolved: this.incidentResolvedSubject.asObservable()
        };
    }

    disconnectFromIncidentStream(): void {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
    }

    getCurrentIncident(): Observable<ReplanningIncident> {
        return this.http.get<ReplanningIncident>(`${API_URL}/replanning-incidents/current`);
    }

    getHistory(): Observable<ReplanningIncident[]> {
        return this.http.get<ReplanningIncident[]>(`${API_URL}/replanning-incidents/history`);
    }

    resolveIncident(id: number, comment: string): Observable<ReplanningIncident> {
        return this.http.post<ReplanningIncident>(
            `${API_URL}/replanning-incidents/${id}/resolve`,
            { comment }
        );
    }
}
