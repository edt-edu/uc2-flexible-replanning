import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { MachineAttributeUpdate } from '../models/machine-attribute.model';
import { TokenLocation } from '../models/token-location.model';
import { MachineTelemetryService } from '../services/machine-telemetry.service';

@Injectable({
    providedIn: 'root'
})
export class MachineTelemetryStore {

    constructor(private readonly machineTelemetryService: MachineTelemetryService) { }

    /**
     * Raw machine attributes: the latest value of each one on each (re)connection, then every change. Emitted outside
     * the Angular zone: drawing them must not trigger a change detection.
     */
    connect(): Observable<MachineAttributeUpdate> {
        return this.machineTelemetryService.connectToMachineStream();
    }

    /** Inferred token location updates (received on the telemetry stream opened by connect()). */
    get tokenUpdates$(): Observable<TokenLocation> {
        return this.machineTelemetryService.tokenUpdates$;
    }

    disconnect(): void {
        this.machineTelemetryService.disconnectFromMachineStream();
    }
}
