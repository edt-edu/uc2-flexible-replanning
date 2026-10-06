import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild } from '@angular/core';
import { Subscription } from 'rxjs';

import { ShopfloorMachineUpdate } from '../../models/machine-update.model';
import { VacuumGripperModel } from '../../models/shopfloor/machines/models/VacummGripper.model';
import { TokenLocation } from '../../models/token-location.model';
import { MachineTelemetryStore } from '../../stores/machine-telemetry.store';

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
                            <span class="h-4 w-4 rounded border border-red-200" style="background:#dc2626"></span>
                            <span>Not operational</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-amber-200" style="background:#fef3c7"></span>
                            <span>Not initialized</span>
                        </li>
                    </ul>

                    <p class="mb-4 mt-6 text-xs text-slate-500">SVG Elements</p>
                    <ul class="space-y-3 text-sm text-slate-700">
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-blue-200" style="background:rgba(59, 130, 246, 0.3)"></span>
                            <span>Access Zone</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-gray-400" style="background:#374151"></span>
                            <span>Moving Parts</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-yellow-300" style="background:#fef9c3"></span>
                            <span>Light Barrier Sensor</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-yellow-500" style="background:#facc15"></span>
                            <span>Light Barrier Triggered</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded border border-blue-300" style="background:#2563eb"></span>
                            <span>Gripper Vacuum On</span>
                        </li>
                    </ul>

                    <p class="mb-4 mt-6 text-xs text-slate-500">Token (inferred by the digital twin)</p>
                    <ul class="space-y-3 text-sm text-slate-700">
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded-full border border-slate-700" style="background:#9ca3af"></span>
                            <span>Color not known yet</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded-full border border-slate-700" style="background:#ffffff"></span>
                            <span class="h-4 w-4 rounded-full border border-slate-700" style="background:#dc2626"></span>
                            <span class="h-4 w-4 rounded-full border border-slate-700" style="background:#2563eb"></span>
                            <span>White / red / blue</span>
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="h-4 w-4 rounded-full border-2 border-dashed border-red-600"></span>
                            <span>Last position before an anomaly</span>
                        </li>
                    </ul>
                    <p class="mt-3 text-xs text-slate-500" data-testid="token-state">
                        {{ tokenDescription }}
                    </p>
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
    private subscription: Subscription | null = null;
    private tokenSubscription: Subscription | null = null;
    private lastToken: TokenLocation | null = null;
    tokenDescription = 'No token';

    /** Inferred token location -> SVG element the token is drawn on. */
    private static readonly TOKEN_ANCHOR_BY_LOCATION: Record<string, string> = {
        'sl-input': 'sortingLineSensInputLightBarrierSLSystemModel',
        'sl-white': 'sortingLineSensWhiteLightBarrierSLSystemModel',
        'sl-red': 'sortingLineSensRedLightBarrierSLSystemModel',
        'sl-blue': 'sortingLineSensBlueLightBarrierSLSystemModel',
        'vgr1': 'vgrEndEffectorVGRSystemModel_01',
        'cb-feed': 'sensorFeedCBSystemModel',
        'cb-swap': 'sensorSwapCBSystemModel',
        'vgr2': 'vgrEndEffectorVGRSystemModel_02',
        'mps-input': 'sensorInMPSystemModel',
        'mps-output': 'sensorOutMPSystemModel'
    };
    private static readonly TOKEN_FILL_BY_COLOR: Record<string, string> = {
        WHITE: '#ffffff',
        RED: '#dc2626',
        BLUE: '#2563eb'
    };
    private static readonly TOKEN_ELEMENT_ID = 'inferredToken';
    private static readonly MACHINE_NAME_BY_ID: Record<string, string> = {
        CBSystemModel: 'Conveyor Belt',
        HBWSystemModel: 'High Bay Warehouse',
        SLSystemModel: 'Sorting Line',
        MPSystemModel: 'Multi Processing Station',
        VGRSystemModel_01: 'Vacuum Gripper Robot 01',
        VGRSystemModel_02: 'Vacuum Gripper Robot 02'
    };

    // Cache machine elements for fast updates
    private readonly machineCache = new Map<string, SVGGElement>();

    constructor(private readonly telemetryStore: MachineTelemetryStore) { }

    ngAfterViewInit(): void {
        // Load SVG into container
        fetch('/assets/shopfloor.svg')
            .then(resp => resp.text())
            .then(svgText => {
                this.svgContainer.nativeElement.innerHTML = svgText;
                const svg = this.svgContainer.nativeElement.querySelector<SVGSVGElement>('svg');
                if (svg) {
                    svg.setAttribute('width', '100%');
                    svg.setAttribute('height', '100%');
                    svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
                }
                this.cacheMachines();
                this.renderToken();
            });

        // Subscribe to telemetry
        this.subscription = this.telemetryStore.connect().subscribe({
            next: update => this.applyUpdate(update),
            error: err => console.error('[Shopfloor] Telemetry error:', err)
        });
        this.tokenSubscription = this.telemetryStore.tokenUpdates$.subscribe(token => {
            this.lastToken = token;
            this.renderToken();
        });
    }

    ngOnDestroy(): void {
        this.subscription?.unsubscribe();
        this.tokenSubscription?.unsubscribe();
        this.telemetryStore.disconnect();
    }

    /** Cache machine elements by ID */
    private cacheMachines(): void {
        const svg = this.svgContainer.nativeElement.querySelector<SVGSVGElement>('svg');
        if (!svg) return;

        svg.querySelectorAll<SVGGElement>('g[id]').forEach(g => {
            this.machineCache.set(g.id, g);
        });

        this.renderInitialMachineState();
        this.attachMachineTooltips(svg);

        console.log('[Shopfloor] Cached machine elements:', this.machineCache.size);
    }

    private renderInitialMachineState(): void {
        console.log(this.machineCache);
        this.machineCache.forEach((machineEl, machineId) => {
            const machine = this.telemetryStore.getMachineById(machineId);
            if (!machine) {
                return;
            }
            if (machine instanceof VacuumGripperModel) {
                // the arm is animated between updates: a token held by the gripper follows it on every frame
                machine.onArmFrame = () => {
                    if (this.lastToken?.location?.startsWith('vgr')) {
                        this.renderToken();
                    }
                };
            }
            machine.render(machineEl);
        });
    }

    private attachMachineTooltips(svg: SVGSVGElement): void {
        const machineGroups = svg.querySelectorAll<SVGGElement>(':scope > g[id]');
        machineGroups.forEach(group => {
            if (!this.isMachineGroup(group.id)) {
                return;
            }

            const tooltipText = FactoryShopfloorLayoutComponent.MACHINE_NAME_BY_ID[group.id] ?? group.id;
            const existingTitle = group.querySelector(':scope > title');
            if (existingTitle) {
                existingTitle.textContent = tooltipText;
                return;
            }

            const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
            title.textContent = tooltipText;
            group.insertBefore(title, group.firstChild);
        });
    }

    private isMachineGroup(groupId: string): boolean {
        return Object.hasOwn(FactoryShopfloorLayoutComponent.MACHINE_NAME_BY_ID, groupId);
    }

    /** Draws the inferred token on the SVG element of its location (none when there is no token). */
    private renderToken(): void {
        const token = this.lastToken;
        this.tokenDescription = !token || !token.location
            ? `No token${token?.state ? ` (${token.state})` : ''}`
            : `${token.color ?? 'Unknown color'} token at ${token.location} (${token.state})${token.anomaly ? ' - anomaly' : ''}`;

        const svg = this.svgContainer.nativeElement.querySelector<SVGSVGElement>('svg');
        if (!svg) return;
        svg.querySelector(`#${FactoryShopfloorLayoutComponent.TOKEN_ELEMENT_ID}`)?.remove();
        if (!token?.location) return;

        const anchorId = FactoryShopfloorLayoutComponent.TOKEN_ANCHOR_BY_LOCATION[token.location];
        const anchor = anchorId ? svg.querySelector<SVGGraphicsElement>(`#${anchorId}`) : null;
        if (!anchor) {
            console.warn(`[Shopfloor] No SVG element for token location '${token.location}'`);
            return;
        }

        // drawn next to the anchor, in the same coordinate system, so it follows the machine's transforms
        const box = anchor.getBBox();
        const svgNs = 'http://www.w3.org/2000/svg';
        const circle = document.createElementNS(svgNs, 'circle');
        circle.setAttribute('id', FactoryShopfloorLayoutComponent.TOKEN_ELEMENT_ID);
        circle.setAttribute('cx', String(box.x + box.width / 2));
        circle.setAttribute('cy', String(box.y + box.height / 2));
        circle.setAttribute('r', '12');
        circle.setAttribute('fill', token.color ? FactoryShopfloorLayoutComponent.TOKEN_FILL_BY_COLOR[token.color] : '#9ca3af');
        circle.setAttribute('stroke', token.anomaly ? '#dc2626' : '#111827');
        circle.setAttribute('stroke-width', token.anomaly ? '3' : '2');
        if (token.anomaly) {
            circle.setAttribute('stroke-dasharray', '4 3');
        }
        const anchorTransform = anchor.getAttribute('transform');
        if (anchorTransform && anchor.tagName.toLowerCase() !== 'g') {
            circle.setAttribute('transform', anchorTransform);
        }
        const title = document.createElementNS(svgNs, 'title');
        title.textContent = this.tokenDescription;
        circle.appendChild(title);

        const container = anchor.tagName.toLowerCase() === 'g' ? anchor : anchor.parentNode;
        container?.appendChild(circle);
    }

    /**
     * Apply a telemetry update to a machine
     */
    private applyUpdate(update: ShopfloorMachineUpdate): void {
        const machine = this.telemetryStore.getMachineById(update.machineId);
        if (!machine) return;

        const machineEl = this.machineCache.get(update.machineId);
        if (!machineEl) return;

        machine.render(machineEl); // model handles the rendering (a VGR then follows its arm, see onArmFrame)
    }
}