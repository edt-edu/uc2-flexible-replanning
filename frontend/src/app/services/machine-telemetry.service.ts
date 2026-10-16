import { Injectable, NgZone } from '@angular/core';
import { Observable, Subject } from 'rxjs';

const API_URL = '/api';
import { fromMachineAttributeUpdate, MachineAttributeUpdate } from '../models/machine-attribute.model';
import { fromTokenLocation, TokenLocation } from '../models/token-location.model';

@Injectable({
    providedIn: 'root'
})
export class MachineTelemetryService {
    private eventSource: EventSource | null = null;
    private readonly machineUpdateSubject = new Subject<MachineAttributeUpdate>();
    private readonly tokenUpdateSubject = new Subject<TokenLocation>();

    /** Inferred token location: the current one on each (re)connection, then every change. */
    readonly tokenUpdates$: Observable<TokenLocation> = this.tokenUpdateSubject.asObservable();

    constructor(private readonly ngZone: NgZone) { }

    connectToMachineStream(): Observable<MachineAttributeUpdate> {
        if (this.eventSource) {
            this.eventSource.close();
        }

        const url = `${API_URL}/machines/stream`;
        console.log('[MachineTelemetryService] Connecting to SSE endpoint:', url);

        const eventSource = new EventSource(url);
        this.eventSource = eventSource;

        // Outside Angular, so that a message does not trigger a change detection: the shopfloor view draws the machines
        // once per animation frame.
        this.ngZone.runOutsideAngular(() => eventSource.addEventListener('machine-attribute', (event: MessageEvent) => {
            try {
                this.machineUpdateSubject.next(fromMachineAttributeUpdate(JSON.parse(event.data)));
            } catch (error) {
                console.error('[MachineTelemetryService] Error parsing machine attribute:', error);
            }
        }));

        this.eventSource.addEventListener('token-update', (event: MessageEvent) => {
            this.ngZone.run(() => {
                try {
                    this.tokenUpdateSubject.next(fromTokenLocation(JSON.parse(event.data)));
                } catch (error) {
                    console.error('[MachineTelemetryService] Error parsing token update:', error);
                }
            });
        });

        this.eventSource.addEventListener('connected', (event: MessageEvent) => {
            console.log('[MachineTelemetryService] SSE connected:', event.data);
            // token-update events only report changes: fetch the current location
            fetch(`${API_URL}/machines/token`)
                .then(response => response.json())
                .then(token => this.ngZone.run(() => this.tokenUpdateSubject.next(fromTokenLocation(token))))
                .catch(error => console.error('[MachineTelemetryService] Error fetching token location:', error));
        });

        this.eventSource.onerror = (error) => {
            console.error('[MachineTelemetryService] SSE connection error:', error);

            if (this.eventSource) {
                this.eventSource.close();
                this.eventSource = null;
            }

            setTimeout(() => {
                this.connectToMachineStream();
            }, 5000);
        };

        return this.machineUpdateSubject.asObservable();
    }

    disconnectFromMachineStream(): void {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
            console.log('[MachineTelemetryService] SSE connection closed');
        }
    }
}
