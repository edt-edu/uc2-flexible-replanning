import { MachineTelemetryData } from "../interfaces/IMachine";
import { GenericMachine } from "./GenericMachine.model";

/** A received VGR state, at the PLC time it was measured. */
interface ArmSample {
    time: number; // epoch ms, PLC clock
    armExtension: number;
    armRotation: number;
    gripperState: boolean;
    isArmElevated: boolean;
}

/**
 * How far behind the latest sample the arm is drawn. Updates reach the browser at irregular intervals (network,
 * gateway, SSE batching): drawing slightly in the past always leaves a later sample to interpolate towards, so the
 * arm moves linearly instead of jumping each time an update arrives. Must exceed the usual gap between updates.
 */
const PLAYBACK_DELAY_MS = 100;
/** Samples kept to estimate the PLC-to-browser clock offset (the smallest observed transit time). */
const CLOCK_OFFSET_WINDOW = 100;
/** Samples older than this, relative to the playback time, are no longer needed to interpolate. */
const SAMPLE_RETENTION_MS = 2000;

export class VacuumGripperModel extends GenericMachine {

    armExtension: number = 0; // in mm
    armRotation: number = 0; // in degrees
    gripperExtension: number = 0; // in mm
    gripperState: boolean = false; // true if gripper is closed, false if open
    isArmElevated: boolean = false;
    isArmExtended: boolean = false;

    /** Called on every animation frame that moves the arm, e.g. so a held token follows the end effector. */
    onArmFrame: (() => void) | null = null;

    private readonly samples: ArmSample[] = [];
    private readonly clockOffsets: number[] = [];
    private animationFrameId: number | null = null;
    private machineEl: SVGGElement | null = null;

    override update(data: MachineTelemetryData): void {
        super.update(data);
        const state = data.value as Record<string, unknown>;

        if (typeof state['armExtension'] === 'number') {
            this.armExtension = state['armExtension'];
        }
        if (typeof state['armRotation'] === 'number') {
            this.armRotation = state['armRotation'];
        }
        if (typeof state['gripperExtension'] === 'number') {
            this.gripperExtension = state['gripperExtension'];
        }
        if (typeof state['gripperState'] === 'boolean') {
            this.gripperState = state['gripperState'];
        }
        if (typeof state['isArmElevated'] === 'boolean') {
            this.isArmElevated = state['isArmElevated'];
        }
        if (typeof state['isArmExtended'] === 'boolean') {
            this.isArmExtended = state['isArmExtended'];
        }

        this.addSample(Date.parse(data.timestamp));
    }

    override render(machineEl: SVGGElement): void {
        super.render(machineEl);
        this.machineEl = machineEl;

        if (this.samples.length === 0) {
            this.drawArm(machineEl, this.currentState());
            return;
        }
        this.animationFrameId ??= requestAnimationFrameOutsideAngular(() => this.animate());
    }

    /** Draws the arm at the playback time, and keeps animating until it has reached the latest sample. */
    private animate(): void {
        this.animationFrameId = null;
        if (!this.machineEl || this.samples.length === 0) {
            return;
        }

        const playbackTime = Date.now() - this.clockOffset() - PLAYBACK_DELAY_MS;
        this.dropSamplesBefore(playbackTime - SAMPLE_RETENTION_MS);
        this.drawArm(this.machineEl, this.stateAt(playbackTime));
        this.onArmFrame?.();

        if (playbackTime < this.samples[this.samples.length - 1].time) {
            this.animationFrameId = requestAnimationFrameOutsideAngular(() => this.animate());
        }
    }

    private addSample(measuredAt: number): void {
        const now = Date.now();
        const time = Number.isFinite(measuredAt) ? measuredAt : now;
        const last = this.samples[this.samples.length - 1];
        if (last && time < last.time) {
            return; // out of order: the arm is already drawn past it
        }

        this.samples.push({ time, ...this.currentState() });
        this.clockOffsets.push(now - time);
        if (this.clockOffsets.length > CLOCK_OFFSET_WINDOW) {
            this.clockOffsets.shift();
        }
    }

    /** Browser clock minus PLC clock, plus the fastest transit time seen recently. */
    private clockOffset(): number {
        return Math.min(...this.clockOffsets);
    }

    private dropSamplesBefore(time: number): void {
        // keep at least the two samples around the playback time
        while (this.samples.length > 2 && this.samples[1].time < time) {
            this.samples.shift();
        }
    }

    /** Linear interpolation of the arm between the samples around `time`; the flags change at their sample time. */
    private stateAt(time: number): Omit<ArmSample, 'time'> {
        const first = this.samples[0];
        if (time <= first.time) {
            return first;
        }
        for (let i = 1; i < this.samples.length; i++) {
            const next = this.samples[i];
            if (time < next.time) {
                const previous = this.samples[i - 1];
                const t = (time - previous.time) / (next.time - previous.time);
                return {
                    ...previous,
                    armExtension: previous.armExtension + t * (next.armExtension - previous.armExtension),
                    armRotation: previous.armRotation + t * (next.armRotation - previous.armRotation)
                };
            }
        }
        return this.samples[this.samples.length - 1];
    }

    private currentState(): Omit<ArmSample, 'time'> {
        return {
            armExtension: this.armExtension,
            armRotation: this.armRotation,
            gripperState: this.gripperState,
            isArmElevated: this.isArmElevated
        };
    }

    private drawArm(machineEl: SVGGElement, arm: Omit<ArmSample, 'time'>): void {
        const rotationGroup = machineEl.querySelector<SVGGElement>(`#vgrArmRotation${this.id}`);
        if (rotationGroup) {
            rotationGroup.setAttribute('transform', `rotate(${arm.armRotation} 214 105)`);
        }

        this.toggleElementClass(machineEl, `vgrEndEffector${this.id}`, 'gripperActive', arm.gripperState);
        this.toggleElementClass(machineEl, `vgrArmBody${this.id}`, 'armLowered', !arm.isArmElevated);

        const armBody = machineEl.querySelector<SVGRectElement>(`#vgrArmBody${this.id}`);
        const endEffector = machineEl.querySelector<SVGRectElement>(`#vgrEndEffector${this.id}`);
        if (armBody && endEffector) {
            const pivotX = 214;
            const minLength = 165;
            const maxLength = 315;
            const endEffectorWidth = 22;
            const clampedLength = Math.max(minLength, Math.min(maxLength, arm.armExtension));
            armBody.setAttribute('width', `${clampedLength}`);
            endEffector.setAttribute('x', `${pivotX + clampedLength - endEffectorWidth}`);
        }
    }
}

/** Animation frames that do not trigger an Angular change detection: they only move SVG elements. */
function requestAnimationFrameOutsideAngular(callback: () => void): number {
    const rootZone = (globalThis as { Zone?: { root: { run<T>(fn: () => T): T } } }).Zone?.root;
    return rootZone ? rootZone.run(() => requestAnimationFrame(callback)) : requestAnimationFrame(callback);
}
