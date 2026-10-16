import { AfterViewInit, Component, ElementRef, NgZone, OnDestroy, ViewChild, effect } from '@angular/core';
import { LEGEND_COLORS, parseLayout, ShopfloorView } from '@mbdo/machines-svg-map';
import { Subscription } from 'rxjs';

import { ReplanningIncident } from '../../models/replanning-incident.model';
import { TokenLocation, TokenOnLine } from '../../models/token-location.model';
import { MachineTelemetryStore } from '../../stores/machine-telemetry.store';
import { ReplanningIncidentStore } from '../../stores/replanning-incident.store';

/** Factory layout drawn by the shopfloor view (machines, telemetry ids, VGR arm calibrations, token locations). */
const LAYOUT_URL = '/assets/shopfloor-layout.json';

@Component({
    selector: 'app-factory-shopfloor-layout',
    template: `
        <div class="shopfloor-layout-root flex h-screen">
            <aside
                id="shopfloor-legend-panel"
                class="legend-panel z-20 h-full w-72 border-r border-slate-200 bg-white"
                aria-label="Shopfloor legend"
            >
                <div class="h-full overflow-y-auto px-4 py-5">
                    <h2 class="mb-1 text-sm font-semibold uppercase tracking-wide text-slate-500">Shopfloor Legend</h2>
                    <p class="mb-4 text-xs text-slate-500">Machine color states</p>

                    <ul class="space-y-3 text-sm text-slate-700">
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-red-200" [style.background]="colors.notOperational"></span>
                            <span>Not operational, or failed (stopped by a replanning)</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-amber-200" [style.background]="colors.notInitialized"></span>
                            <span>Not initialized</span>
                        </li>
                    </ul>

                    <p class="mb-4 mt-6 text-xs text-slate-500">SVG Elements</p>
                    <ul class="space-y-3 text-sm text-slate-700">
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-blue-200" [style.background]="colors.accessZone"></span>
                            <span>Access Zone</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-gray-400" [style.background]="colors.movingPart"></span>
                            <span>Moving Parts</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-yellow-300" [style.background]="colors.lightBarrier"></span>
                            <span>Light Barrier Sensor</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-yellow-500" [style.background]="colors.lightBarrierTriggered"></span>
                            <span>Light Barrier Triggered</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-blue-300" [style.background]="colors.gripperVacuumOn"></span>
                            <span>Gripper Vacuum On</span>
                        </li>
                    </ul>

                    <p class="mb-4 mt-6 text-xs text-slate-500">Token (inferred by the digital twin)</p>
                    <ul class="space-y-3 text-sm text-slate-700">
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded-full border border-slate-700" [style.background]="colors.tokenUnknownColor"></span>
                            <span>Color not known yet</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded-full border border-slate-700" [style.background]="colors.tokenWhite"></span>
                            <span class="h-4 w-4 rounded-full border border-slate-700" [style.background]="colors.tokenRed"></span>
                            <span class="h-4 w-4 rounded-full border border-slate-700" [style.background]="colors.tokenBlue"></span>
                            <span>White / red / blue</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded-full border-2 border-dashed border-red-600"></span>
                            <span>Last position before an anomaly</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded-full border-2 border-dashed border-slate-400 opacity-60"></span>
                            <span>Trace: last known position, the sensor no longer sees the token (until the incident is resolved)</span>
                        </li>
                    </ul>
                    <ul class="mt-3 space-y-1 text-xs text-slate-500" data-testid="token-state">
                        @for (line of tokenDescriptions; track line) {
                            <li>{{ line }}</li>
                        } @empty {
                            <li>No token</li>
                        }
                    </ul>
                </div>
            </aside>

            <div #svgContainer class="shopfloor-container flex-1"></div>
        </div>
    `,
    styleUrls: ['./factory-shopfloor-layout.component.css'],
    standalone: true
})
export class FactoryShopfloorLayoutComponent implements AfterViewInit, OnDestroy {
    @ViewChild('svgContainer', { static: true }) svgContainer!: ElementRef<HTMLElement>;
    readonly colors = LEGEND_COLORS;
    tokenDescriptions: string[] = [];

    /** SCADA machine (as the replanning plans name it) -> machine of the layout. */
    private static readonly LAYOUT_ID_BY_SCADA_MACHINE: Record<string, string> = {
        'REN_MISSION_01/CB01': 'CBSystemModel',
        'REN_MISSION_01/SL01': 'SLSystemModel',
        'REN_MISSION_01/MPS01': 'MPSystemModel',
        'REN_MISSION_01/VGR01': 'VGRSystemModel_01',
        'REN_MISSION_01/VGR02': 'VGRSystemModel_02'
    };

    private view: ShopfloorView | null = null;
    private subscription: Subscription | null = null;
    private tokenSubscription: Subscription | null = null;
    private destroyed = false;
    /** The machines the open replanning incident found failed, and since when. */
    private failedMachines = new Map<string, Date>();

    constructor(
        private readonly telemetryStore: MachineTelemetryStore,
        private readonly ngZone: NgZone,
        replanningIncidentStore: ReplanningIncidentStore
    ) {
        effect(() => this.renderFailures(replanningIncidentStore.currentIncident()));
    }

    ngAfterViewInit(): void {
        fetch(LAYOUT_URL)
            .then(response => response.json())
            .then(raw => {
                if (this.destroyed) return;
                const { layout, warnings } = parseLayout(raw, LAYOUT_URL);
                warnings.forEach(warning => console.warn(`[Shopfloor] ${warning}`));
                this.view = this.ngZone.runOutsideAngular(() => ShopfloorView.mount(this.svgContainer.nativeElement, layout));
                this.view.setFailedMachines(this.failedMachines);
                // connected once the view is there: the stream starts with the latest value of every attribute
                this.connect();
            })
            .catch(error => console.error(`[Shopfloor] Cannot load the layout ${LAYOUT_URL}:`, error));
    }

    ngOnDestroy(): void {
        this.destroyed = true;
        this.subscription?.unsubscribe();
        this.tokenSubscription?.unsubscribe();
        this.telemetryStore.disconnect();
        this.view?.destroy();
    }

    private connect(): void {
        this.subscription = this.telemetryStore.connect().subscribe({
            next: update => this.view?.applyAttribute(update.machineId, update.attribute, update.value, update.timestamp),
            error: err => console.error('[Shopfloor] Telemetry error:', err)
        });
        this.tokenSubscription = this.telemetryStore.tokenUpdates$.subscribe(token => this.renderTokens(token));
    }

    /**
     * Shows as failed the machines the open incident's replanning stops (e.g. the conveyor belt that doesn't move the
     * token from its feed), until the incident is resolved.
     */
    private renderFailures(incident: ReplanningIncident | null): void {
        this.failedMachines = FactoryShopfloorLayoutComponent.failedMachinesOf(incident);
        this.view?.setFailedMachines(this.failedMachines);
    }

    private static failedMachinesOf(incident: ReplanningIncident | null): Map<string, Date> {
        const failed = new Map<string, Date>();
        if (!incident || incident.status !== 'OPEN') {
            return failed;
        }
        const since = new Date(incident.startTime);
        for (const step of incident.replanningPlan?.steps ?? []) {
            const machineId = FactoryShopfloorLayoutComponent.LAYOUT_ID_BY_SCADA_MACHINE[step.machine];
            if (step.kind === 'STOP_MISSION' && machineId) {
                failed.set(machineId, since);
            }
        }
        return failed;
    }

    /** Draws each inferred token on the SVG element of its location, with its number. */
    private renderTokens(token: TokenLocation): void {
        const tokens = token.tokens;
        this.tokenDescriptions = tokens.map(onLine => FactoryShopfloorLayoutComponent.describeToken(onLine));
        this.view?.setTokens(tokens.map(onLine => ({
            location: onLine.location,
            color: onLine.color,
            anomaly: onLine.anomaly,
            present: onLine.present,
            number: onLine.number,
            title: FactoryShopfloorLayoutComponent.describeToken(onLine)
        })));
    }

    private static describeToken(token: TokenOnLine): string {
        const where = !token.location ? 'off the line'
            : token.present ? `at ${token.location}` : `last seen at ${token.location}`;
        return `#${token.number} ${token.color ?? 'Unknown color'} token ${where} (${token.state})`
            + (token.anomaly ? ' - anomaly' : '');
    }
}
