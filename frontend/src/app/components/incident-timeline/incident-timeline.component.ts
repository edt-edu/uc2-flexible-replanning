import { Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';

import { ReplanningIncident } from '../../models/replanning-incident.model';

/** Width of the sliding window shown by the chart. */
const WINDOW_MS = 60 * 60 * 1000;
/** How often "now" moves forward, so open incidents grow and old ones slide out. */
const REFRESH_MS = 15 * 1000;
/** Spacing of the time-axis ticks (aligned on the clock: :00, :10, :20 ...). */
const TICK_MS = 10 * 60 * 1000;
/** Smallest bar width (in % of the track) so a very short incident stays clickable. */
const MIN_BAR_PERCENT = 0.8;

@Component({
    selector: 'app-incident-timeline',
    standalone: true,
    imports: [DatePipe],
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
    readonly windowStart = computed(() => this.now() - WINDOW_MS);

    /** Current + history merged by id (the live version wins), oldest start first. */
    readonly incidents = computed(() => {
        const current = this.currentIncident();
        const all = current ? [...this.history(), current] : this.history();
        return [...new Map(all.map(incident => [incident.id, incident])).values()]
            .sort((a, b) => this.startOf(a) - this.startOf(b));
    });

    readonly bars = computed(() => {
        const start = this.windowStart();
        const end = this.now();
        const laneEnds: number[] = [];
        return this.incidents().flatMap(incident => {
            const from = this.startOf(incident);
            const to = this.endOf(incident);
            if (Number.isNaN(from) || to < start || from > end) {
                return [];
            }
            const shownFrom = Math.max(from, start);
            const shownTo = Math.min(Math.max(to, from), end);
            // Greedy lane assignment so overlapping incidents do not hide each other.
            let lane = laneEnds.findIndex(laneEnd => laneEnd < shownFrom);
            if (lane === -1) {
                lane = laneEnds.length;
            }
            laneEnds[lane] = shownTo;
            const width = Math.max(((shownTo - shownFrom) / WINDOW_MS) * 100, MIN_BAR_PERCENT);
            return [{
                incident,
                lane,
                width,
                left: Math.min(((shownFrom - start) / WINDOW_MS) * 100, 100 - width),
                happening: this.isHappening(incident),
                clippedStart: from < start,
                clippedEnd: to > end
            }];
        });
    });

    readonly laneCount = computed(() => Math.max(1, ...this.bars().map(bar => bar.lane + 1)));
    readonly happeningCount = computed(() => this.bars().filter(bar => bar.happening).length);

    /** Tick positions, in % of the track. */
    readonly ticks = computed(() => {
        const start = this.windowStart();
        const ticks: { time: number; left: number }[] = [];
        for (let time = Math.ceil(start / TICK_MS) * TICK_MS; time <= this.now(); time += TICK_MS) {
            ticks.push({ time, left: ((time - start) / WINDOW_MS) * 100 });
        }
        return ticks;
    });

    readonly selectedIncident = computed(() =>
        this.incidents().find(incident => incident.id === this.selectedId()) ?? null);

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

    isHappening(incident: ReplanningIncident): boolean {
        return incident.status === 'OPEN';
    }

    startOf(incident: ReplanningIncident): number {
        return Date.parse(incident.startTime ?? incident.createdAt);
    }

    /** End of the incident: its resolution time, or now while it is still happening. */
    endOf(incident: ReplanningIncident): number {
        const resolved = this.isHappening(incident) ? NaN : Date.parse(incident.resolvedAt ?? '');
        return Number.isNaN(resolved) ? this.now() : resolved;
    }

    durationOf(incident: ReplanningIncident): string {
        const ms = this.endOf(incident) - this.startOf(incident);
        if (!(ms >= 0)) {
            return '–';
        }
        const seconds = Math.floor(ms / 1000);
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor(seconds / 60) % 60;
        return hours ? `${hours} h ${minutes} min` : minutes ? `${minutes} min ${seconds % 60} s` : `${seconds} s`;
    }
}
