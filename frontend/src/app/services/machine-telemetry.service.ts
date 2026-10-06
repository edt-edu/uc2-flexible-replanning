import { Injectable, NgZone } from '@angular/core';
import { Observable, Subject } from 'rxjs';

const API_URL = '/api';
import { fromMachineUpdate, MachineStateUpdate } from '../models/machine-update.model';
import { fromTokenLocation, TokenLocation } from '../models/token-location.model';

@Injectable({
    providedIn: 'root'
})
export class MachineTelemetryService {
    private eventSource: EventSource | null = null;
    private readonly machineUpdateSubject = new Subject<MachineStateUpdate>();
    private readonly tokenUpdateSubject = new Subject<TokenLocation>();
    /** Latest not-yet-rendered update of each machine, emitted once per animation frame. */
    private readonly pendingMachineUpdates = new Map<string, MachineStateUpdate>();
    private animationFrameId: number | null = null;

    /** Inferred token location: the current one on each (re)connection, then every change. */
    readonly tokenUpdates$: Observable<TokenLocation> = this.tokenUpdateSubject.asObservable();

    constructor(private readonly ngZone: NgZone) { }

    connectToMachineStream(): Observable<MachineStateUpdate> {
        if (this.eventSource) {
            this.eventSource.close();
        }

        const url = `${API_URL}/machines/stream`;
        console.log('[MachineTelemetryService] Connecting to SSE endpoint:', url);

        const eventSource = new EventSource(url);
        this.eventSource = eventSource;

        // Outside Angular, so that a message does not trigger a change detection: machine updates are rendered once
        // per animation frame instead (see flushMachineUpdates).
        this.ngZone.runOutsideAngular(() => eventSource.addEventListener('machine-update', (event: MessageEvent) => {
            try {
                const machineUpdate = fromMachineUpdate(JSON.parse(event.data));
                this.pendingMachineUpdates.set(machineUpdate.machineId, machineUpdate);
                this.animationFrameId ??= requestAnimationFrame(() => this.flushMachineUpdates());
            } catch (error) {
                console.error('[MachineTelemetryService] Error parsing machine update:', error);
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

    /** Renders the latest state of each machine that changed since the previous frame, in one change detection. */
    private flushMachineUpdates(): void {
        this.animationFrameId = null;
        const updates = [...this.pendingMachineUpdates.values()];
        this.pendingMachineUpdates.clear();
        this.ngZone.run(() => updates.forEach(update => this.machineUpdateSubject.next(update)));
    }

    disconnectFromMachineStream(): void {
        if (this.animationFrameId !== null) {
            cancelAnimationFrame(this.animationFrameId);
            this.animationFrameId = null;
        }
        this.pendingMachineUpdates.clear();
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
            console.log('[MachineTelemetryService] SSE connection closed');
        }
    }
}
