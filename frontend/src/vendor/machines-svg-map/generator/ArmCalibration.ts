import { VGR_ARM } from '../machines/VacuumGripper.js';
import { FactoryLayout, isMachine } from '../layout/FactoryLayout.js';
import { applyLinear, applyMatrix, invert, localCenter, Matrix, SvgNode, walk } from '../svg/SvgNode.js';
import { buildScene } from './generateSvg.js';

/** A named position of a VGR and how its arm is drawn there. */
export interface ResolvedArmAnchor {
  name: string;
  rotationCounts: number;
  horizontalCounts: number;
  target: string;
  /** Rotation of the `vgrArmRotation` group, in degrees (SVG: positive is clockwise). */
  angle: number;
  /** Arm length from the pivot, in px, at most the reach ring. */
  length: number;
}

/**
 * The rotation axis goes from 0 (its reference position, drawn at 0 degrees) to this many encoder counts.
 * Flexible-replanning adaptation, not in the library yet (see ../README.md).
 */
export const MAX_ROTATION_COUNTS = 2990;

/**
 * Maps a VGR's encoder counts to how its arm is drawn, so that the arm points at the drawn machine when the gripper is
 * at one of its named positions (the positions the SCADA missions send by name).
 *
 * Each anchor is a named position (rotation and horizontal encoder counts) with the arm angle / length that put the
 * end effector on the SVG element of that position, computed from the generated drawing. Between anchors the angle is
 * interpolated linearly on the rotation counts. Below the first anchor it goes linearly to 0 degrees at 0 counts (the
 * rotation's reference position), above the last one it continues with the average slope; the rotation counts are
 * clamped to [0, {@link MAX_ROTATION_COUNTS}], the range of the rotation axis. The drawing is not to scale, so the arm
 * length is not interpolated on the horizontal counts alone: at a rotation, the anchor length (interpolated on the
 * rotation) is reached at the anchor's horizontal counts, so the arm only grows as it extends and is exact on each
 * anchor.
 */
export class ArmCalibration {
  private readonly averageSlope: number;

  constructor(readonly anchors: ResolvedArmAnchor[]) {
    if (anchors.length === 0) {
      throw new Error('An arm calibration needs at least one anchor');
    }
    this.anchors = [...anchors].sort((a, b) => a.rotationCounts - b.rotationCounts);
    const first = this.anchors[0];
    const last = this.anchors[this.anchors.length - 1];
    this.averageSlope = last.rotationCounts === first.rotationCounts
      ? 0
      : (last.angle - first.angle) / (last.rotationCounts - first.rotationCounts);
  }

  /** Arm rotation, in degrees, at these rotation encoder counts. */
  angle(rotationCounts: number): number {
    rotationCounts = Math.max(0, Math.min(MAX_ROTATION_COUNTS, rotationCounts));
    const first = this.anchors[0];
    const last = this.anchors[this.anchors.length - 1];
    if (rotationCounts <= first.rotationCounts) {
      return first.rotationCounts === 0 ? first.angle : first.angle * rotationCounts / first.rotationCounts;
    }
    if (rotationCounts >= last.rotationCounts) {
      return last.angle + (rotationCounts - last.rotationCounts) * this.averageSlope;
    }
    return this.interpolate(rotationCounts, a => a.angle);
  }

  /** Arm length, in px, at these rotation and horizontal encoder counts. */
  length(rotationCounts: number, horizontalCounts: number): number {
    const anchorHorizontal = this.interpolate(rotationCounts, a => a.horizontalCounts);
    const anchorLength = this.interpolate(rotationCounts, a => a.length);
    const ratio = anchorHorizontal > 0 ? horizontalCounts / anchorHorizontal : 0;
    const length = VGR_ARM.minLength + ratio * (anchorLength - VGR_ARM.minLength);
    return Math.max(VGR_ARM.minLength, Math.min(VGR_ARM.maxLength, length));
  }

  /** Linear interpolation of `value` between the anchors around `rotationCounts`, clamped at the ends. */
  private interpolate(rotationCounts: number, value: (anchor: ResolvedArmAnchor) => number): number {
    let previous = this.anchors[0];
    if (rotationCounts <= previous.rotationCounts) {
      return value(previous);
    }
    for (const next of this.anchors.slice(1)) {
      if (rotationCounts <= next.rotationCounts) {
        const t = (rotationCounts - previous.rotationCounts) / (next.rotationCounts - previous.rotationCounts);
        return value(previous) + t * (value(next) - value(previous));
      }
      previous = next;
    }
    return value(this.anchors[this.anchors.length - 1]);
  }
}

/**
 * Arm calibration of every VGR of the layout that has an `armCalibration`, keyed by machine id. Throws when an anchor
 * targets an element that is not drawn.
 *
 * @param scene the generated drawing of the layout, built when not given
 */
export function computeArmCalibrations(layout: FactoryLayout, scene: SvgNode = buildScene(layout)): Record<string, ArmCalibration> {
  const centers = new Map<string, [number, number]>();
  const transforms = new Map<string, Matrix>();
  walk(scene, (node, toMap) => {
    const id = node.attrs['id'];
    if (typeof id !== 'string') {
      return;
    }
    transforms.set(id, toMap);
    const center = localCenter(node);
    if (center) {
      centers.set(id, applyMatrix(toMap, center[0], center[1]));
    }
  });

  const calibrations: Record<string, ArmCalibration> = {};
  for (const vgr of layout.positions) {
    if (!isMachine(vgr) || !vgr.armCalibration) {
      continue;
    }
    const toMap = transforms.get(vgr.id)!;
    const pivot = applyMatrix(toMap, VGR_ARM.pivotX, VGR_ARM.pivotY);
    const toVgr = invert(toMap);
    const endEffectorHalfWidth = VGR_ARM.endEffectorWidth / 2;

    const anchors = [...vgr.armCalibration]
      .sort((a, b) => a.rotationCounts - b.rotationCounts)
      .map(anchor => {
        const target = centers.get(anchor.target);
        if (!target) {
          throw new Error(`${vgr.id}.armCalibration ${anchor.name}: no SVG element '${anchor.target}' to point the arm at`);
        }
        // direction in the VGR's own coordinates, where the arm group is rotated
        const [dx, dy] = applyLinear(toVgr, target[0] - pivot[0], target[1] - pivot[1]);
        return {
          ...anchor,
          angle: Math.atan2(dy, dx) * 180 / Math.PI,
          // the end effector center is half its width before the arm end
          length: Math.min(VGR_ARM.maxLength, Math.hypot(dx, dy) + endEffectorHalfWidth),
        };
      });
    // the arm turns continuously with the counts: unwrap the angles so consecutive anchors are less than 180° apart
    for (let i = 1; i < anchors.length; i++) {
      anchors[i].angle -= 360 * Math.round((anchors[i].angle - anchors[i - 1].angle) / 360);
    }
    calibrations[vgr.id] = new ArmCalibration(anchors);
  }
  return calibrations;
}
