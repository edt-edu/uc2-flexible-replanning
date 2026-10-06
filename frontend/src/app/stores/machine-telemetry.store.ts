import { Injectable, computed, signal } from '@angular/core';
import { Observable, Subject, Subscription } from 'rxjs';

import {
    MachineStateUpdate,
    ShopfloorMachineUpdate,
    toShopfloorMachineUpdate
} from '../models/machine-update.model';
import { MachineTelemetryService } from '../services/machine-telemetry.service';
import { TokenLocation } from '../models/token-location.model';
import { IMachine } from '../models/shopfloor/machines/interfaces/IMachine';
import { RENNES_FACTORY_MACHINE_MODELS_BY_ID } from '../models/shopfloor/config/RennesFactorySetup1.js';

@Injectable({
    providedIn: 'root'
})
export class MachineTelemetryStore {
    private readonly machineEntitiesState = signal<Record<string, IMachine>>({
        ...RENNES_FACTORY_MACHINE_MODELS_BY_ID
    });
    private readonly updatesSubject = new Subject<ShopfloorMachineUpdate>();

    private telemetrySubscription: Subscription | null = null;

    readonly machineEntities = this.machineEntitiesState.asReadonly();
    readonly machineIds = computed(() => Object.keys(this.machineEntitiesState()));

    constructor(private readonly machineTelemetryService: MachineTelemetryService) { }

    connect(): Observable<ShopfloorMachineUpdate> {
        if (!this.telemetrySubscription) {
            this.telemetrySubscription = this.machineTelemetryService.connectToMachineStream().subscribe({
                next: (rawUpdate) => {
                    this.processUpdate(rawUpdate);
                },
                error: (error) => {
                    console.error('[MachineTelemetryStore] Machine telemetry stream error:', error);
                }
            });
        }

        return this.updatesSubject.asObservable();
    }

    /** Inferred token location updates (received on the telemetry stream opened by connect()). */
    get tokenUpdates$(): Observable<TokenLocation> {
        return this.machineTelemetryService.tokenUpdates$;
    }

    getMachineById(machineId: string): IMachine | undefined {
        const normalizedMachineId = machineId.trim();
        if (!normalizedMachineId) {
            return undefined;
        }

        const currentMachines = this.machineEntitiesState();
        return currentMachines[normalizedMachineId] ?? RENNES_FACTORY_MACHINE_MODELS_BY_ID[normalizedMachineId];
    }

    disconnect(): void {
        if (this.telemetrySubscription) {
            this.telemetrySubscription.unsubscribe();
            this.telemetrySubscription = null;
        }

        this.machineTelemetryService.disconnectFromMachineStream();
    }

    private processUpdate(rawUpdate: MachineStateUpdate): void {
        const update = toShopfloorMachineUpdate(rawUpdate);
        const machineId = update.machineId.trim();

        if (!machineId) {
            return;
        }

        this.machineEntitiesState.update((current) => {
            const currentEntity = current[machineId] ?? RENNES_FACTORY_MACHINE_MODELS_BY_ID[machineId];
            if (!currentEntity) {
                console.warn(`[MachineTelemetryStore] Ignoring update for unknown machine id '${machineId}'`);
                return current;
            }

            currentEntity.update({
                attribute: 'state',
                value: update.state,
                timestamp: update.timestamp,
                rawUpdate
            });

            return {
                ...current,
                [machineId]: currentEntity
            };
        });

        this.updatesSubject.next(update);
    }
}
