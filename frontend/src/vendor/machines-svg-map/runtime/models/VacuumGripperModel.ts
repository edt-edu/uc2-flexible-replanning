import { ArmCalibration } from '../../generator/ArmCalibration.js';
import { VGR_ARM } from '../../machines/VacuumGripper.js';
import { asBoolean, asNumber } from '../attributes.js';
import { byId, MachineModel } from './MachineModel.js';

const MAX_VERTICAL_ENCODER_COUNTER = 1750;
const MAX_ARM_ENCODER_COUNTER = 1970;
const MAX_ROT_ENCODER_COUNTER = 3040;
const MAX_ARM_ROTATION_DEG = 360;

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

/**
 * VGR: the arm is drawn from the rotation / horizontal encoder counts, with the layout's calibration of this VGR
 * (see {@link ArmCalibration}), or a linear mapping on the encoder ranges without one.
 */
export class VacuumGripperModel extends MachineModel {
  /** Arm length from the pivot, in px. */
  armExtension: number = VGR_ARM.minLength;
  /** Arm rotation in degrees (SVG: positive is clockwise). */
  armRotation = 0;
  gripperExtension = 0;
  /** The vacuum holds a workpiece. */
  gripperState = false;
  isArmElevated = false;
  isArmExtended = false;

  private rotationCounts = 0;
  private horizontalCounts = 0;

  constructor(id: string, private readonly calibration?: ArmCalibration) {
    super(id);
    this.applyCalibratedArm();
  }

  protected applyKindAttribute(attribute: string, value: unknown): void {
    const bool = asBoolean(value);
    if (bool !== null) {
      // vacuumActValve: the valve holds the vacuum, i.e. the gripper holds a workpiece
      if (attribute.includes('gripperstate') || attribute.includes('vacuumactvalve')) {
        this.gripperState = bool;
        return;
      }
      if (attribute.includes('armelevated')) {
        this.isArmElevated = bool;
        return;
      }
      if (attribute.includes('armextended')) {
        this.isArmExtended = bool;
        return;
      }
    }

    const number = asNumber(value);
    if (number === null) {
      return;
    }
    // both the PLC names (vacuumSens*EncoderCounter) and the gateway aliases (*Encoder)
    if (attribute.includes('verticalencoder')) {
      this.isArmElevated = clamp(number, 0, MAX_VERTICAL_ENCODER_COUNTER) / MAX_VERTICAL_ENCODER_COUNTER >= 0.5;
    } else if (attribute.includes('armencoder')) {
      this.applyArmCounter(number);
    } else if (attribute.includes('rotencoder')) {
      this.applyRotationCounter(number);
    } else if (attribute.includes('armextension')) {
      this.armExtension = clamp(number, VGR_ARM.minLength, VGR_ARM.maxLength);
      this.isArmExtended = this.armExtension > VGR_ARM.minLength;
    } else if (attribute.includes('armrotation')) {
      this.armRotation = number;
    } else if (attribute.includes('gripperextension')) {
      this.gripperExtension = number;
    }
  }

  protected override onInitializedFromFeedback(): void {
    // after SETUP every axis is on its reference switch: encoders at 0
    this.rotationCounts = 0;
    this.horizontalCounts = 0;
    this.armRotation = 0;
    this.armExtension = VGR_ARM.minLength;
    this.applyCalibratedArm();
    this.isArmElevated = true;
    this.isArmExtended = false;
  }

  protected kindState(): Record<string, unknown> {
    const { armExtension, armRotation, gripperExtension, gripperState, isArmElevated, isArmExtended } = this;
    return { armExtension, armRotation, gripperExtension, gripperState, isArmElevated, isArmExtended };
  }

  protected renderKind(machineEl: SVGGElement): void {
    byId(machineEl, `vgrArmRotation${this.id}`)
      ?.setAttribute('transform', `rotate(${this.armRotation} ${VGR_ARM.pivotX} ${VGR_ARM.pivotY})`);
    this.toggleClass(machineEl, `vgrEndEffector${this.id}`, 'gripperActive', this.gripperState);
    this.toggleClass(machineEl, `vgrArmBody${this.id}`, 'armLowered', !this.isArmElevated);

    const armBody = byId(machineEl, `vgrArmBody${this.id}`);
    const endEffector = byId(machineEl, `vgrEndEffector${this.id}`);
    if (armBody && endEffector) {
      const length = clamp(this.armExtension, VGR_ARM.minLength, VGR_ARM.maxLength);
      armBody.setAttribute('width', `${length}`);
      endEffector.setAttribute('x', `${VGR_ARM.pivotX + length - VGR_ARM.endEffectorWidth}`);
    }
  }

  private applyArmCounter(counts: number): void {
    this.horizontalCounts = counts;
    if (this.applyCalibratedArm()) {
      return;
    }
    const ratio = clamp(counts, 0, MAX_ARM_ENCODER_COUNTER) / MAX_ARM_ENCODER_COUNTER;
    this.armExtension = VGR_ARM.minLength + ratio * (VGR_ARM.maxLength - VGR_ARM.minLength);
    this.isArmExtended = this.armExtension > VGR_ARM.minLength;
  }

  private applyRotationCounter(counts: number): void {
    this.rotationCounts = counts;
    if (this.applyCalibratedArm()) {
      return;
    }
    // SVG positive rotation is clockwise: counter growth turns the arm counterclockwise
    this.armRotation = -(clamp(counts, 0, MAX_ROT_ENCODER_COUNTER) / MAX_ROT_ENCODER_COUNTER * MAX_ARM_ROTATION_DEG);
  }

  /** Draws the arm from the current encoder counts with this VGR's calibration; false if it has none. */
  private applyCalibratedArm(): boolean {
    if (!this.calibration) {
      return false;
    }
    this.armRotation = this.calibration.angle(this.rotationCounts);
    this.armExtension = this.calibration.length(this.rotationCounts, this.horizontalCounts);
    this.isArmExtended = this.armExtension > VGR_ARM.minLength;
    return true;
  }
}
