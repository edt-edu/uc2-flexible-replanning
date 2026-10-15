import { Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';

import { ReplanningIncident } from '../../models/replanning-incident.model';

/** Width of the sliding window shown by the chart. */
const WINDOW_MS = 60 * 60 * 1000;
/** How often "now" moves forward, so open incidents grow and old ones slide out. */
const REFRESH_MS = 15 * 1000;
/** Spacing of the time-axis ticks (aligned on the clock: :00, :10, :20 ...). */
const TICK_MS = 10 * 60 * 1000;
/** Smallest bar width (in % of the track) so a very short incident stays clickable. */
const MIN_BAR_PERCENT = 0.8;

export interface TimelineBar {
    incident: ReplanningIncident;
    happening: boolean;
    lane: number;
    leftPercent: number;
    widthPercent: number;
    clippedStart: boolean;
    clippedEnd: boolean;
    ariaLabel: string;
}

export interface TimelineTick {
    time: number;
    leftPercent: number;
}

@Component({
    selector: 'app-incident-timeline',
    standalone: true,
    imports: [DatePipe, NgTemplateOutlet],
    templateUrl: './incident-timeline.component.html',
    styleUrls: ['./incident-timeline.component.css']
})
export class IncidentTimelineComponent {
    /** The live (OPEN) incident, if any. */
    readonly currentIncident = input<ReplanningIncident | null>(null);
    /** Resolved incidents, newest first. */
    readonly history = input<ReplanningIncident[]>([]);

    readonly now = signal(Date.now());
    readonly selectedId = signal<number | null>(null);
    readonly detailsExpanded = signal(true);

    readonly windowEnd = computed(() => this.now());
    readonly windowStart = computed(() => this.windowEnd() - WINDOW_MS);

    /** Current + history merged by id (the live version wins), oldest start first. */
    readonly incidents = computed(() => {
        const byId = new Map<number, ReplanningIncident>();
        for (const incident of this.history()) {
            byId.set(incident.id, incident);
        }
        const current = this.currentIncident();
        if (current) {
            byId.set(current.id, current);
        }
        return [...byId.values()].sort((a, b) => this.startOf(a) - this.startOf(b));
    });

    readonly bars = computed<TimelineBar[]>(() => {
        const start = this.windowStart();
        const end = this.windowEnd();
        const laneEnds: number[] = [];
        const bars: TimelineBar[] = [];

        for (const incident of this.incidents()) {
            const happening = this.isHappening(incident);
            const incidentStart = this.startOf(incident);
            const incidentEnd = happening ? end : this.endOf(incident, end);
            if (Number.isNaN(incidentStart) || incidentEnd < start || incidentStart > end) {
                continue;
            }

            const clippedFrom = Math.max(incidentStart, start);
            const clippedTo = Math.min(Math.max(incidentEnd, incidentStart), end);

            // Greedy lane assignment so overlapping incidents do not hide each other.
            let lane = laneEnds.findIndex(laneEnd => laneEnd < clippedFrom);
            if (lane === -1) {
                lane = laneEnds.length;
                laneEnds.push(clippedTo);
            } else {
                laneEnds[lane] = clippedTo;
            }

            let leftPercent = ((clippedFrom - start) / WINDOW_MS) * 100;
            const widthPercent = Math.max(((clippedTo - clippedFrom) / WINDOW_MS) * 100, MIN_BAR_PERCENT);
            leftPercent = Math.min(leftPercent, 100 - widthPercent);

            bars.push({
                incident,
                happening,
                lane,
                leftPercent,
                widthPercent,
                clippedStart: incidentStart < start,
                clippedEnd: !happening && incidentEnd > end,
                ariaLabel: this.ariaLabelFor(incident, happening)
            });
        }
        return bars;
    });

    readonly laneCount = computed(() => Math.max(1, ...this.bars().map(bar => bar.lane + 1)));
    readonly happeningCount = computed(() => this.bars().filter(bar => bar.happening).length);

    readonly ticks = computed<TimelineTick[]>(() => {
        const start = this.windowStart();
        const end = this.windowEnd();
        const ticks: TimelineTick[] = [];
        for (let time = Math.ceil(start / TICK_MS) * TICK_MS; time <= end; time += TICK_MS) {
            ticks.push({ time, leftPercent: ((time - start) / WINDOW_MS) * 100 });
        }
        return ticks;
    });

    readonly selectedIncident = computed(() => {
        const id = this.selectedId();
        return id === null ? null : this.incidents().find(incident => incident.id === id) ?? null;
    });

    constructor() {
        const timer = setInterval(() => this.now.set(Date.now()), REFRESH_MS);
        inject(DestroyRef).onDestroy(() => clearInterval(timer));

        // A new/updated/resolved incident arrives between ticks: move "now" so it is drawn at once.
        effect(() => {
            this.currentIncident();
            this.history();
            untracked(() => this.now.set(Date.now()));
        });
    }

    select(incident: ReplanningIncident): void {
        this.selectedId.set(incident.id);
        this.detailsExpanded.set(true);
    }

    toggleDetails(): void {
        this.detailsExpanded.update(expanded => !expanded);
    }

    isHappening(incident: ReplanningIncident): boolean {
        return incident.status === 'OPEN';
    }

    startOf(incident: ReplanningIncident): number {
        return Date.parse(incident.startTime ?? incident.createdAt);
    }

    /** End of the incident: resolution time, or `fallback` (now) while it is still happening. */
    endOf(incident: ReplanningIncident, fallback = this.now()): number {
        if (this.isHappening(incident) || !incident.resolvedAt) {
            return fallback;
        }
        const resolved = Date.parse(incident.resolvedAt);
        return Number.isNaN(resolved) ? fallback : resolved;
    }

    durationOf(incident: ReplanningIncident): string {
        return this.formatDuration(this.endOf(incident) - this.startOf(incident));
    }

    formatDuration(ms: number): string {
        if (!Number.isFinite(ms) || ms < 0) {
            return '–';
        }
        const totalSeconds = Math.floor(ms / 1000);
        const hours = Math.floor(totalSeconds / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;
        if (hours) {
            return `${hours} h ${minutes} min`;
        }
        if (minutes) {
            return `${minutes} min ${seconds} s`;
        }
        return `${seconds} s`;
    }

    private ariaLabelFor(incident: ReplanningIncident, happening: boolean): string {
        const status = happening ? 'happening' : 'ended';
        return `Incident on ${incident.machineId}, ${status}, duration ${this.durationOf(incident)}: ${incident.message}. Show details`;
    }
}
