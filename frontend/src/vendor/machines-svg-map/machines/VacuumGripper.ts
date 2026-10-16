import { el } from '../svg/SvgNode.js';
import { axisCross, ElementDrawing, group, rect, woodBase } from './shapes.js';

/** Geometry of the VGR arm, shared with the runtime that rotates and extends it. */
export const VGR_ARM = {
  pivotX: 214,
  pivotY: 105,
  /** Arm length range, from the pivot to the arm end: the blue reach ring of the drawing. */
  minLength: 165,
  maxLength: 315,
  thickness: 18,
  endEffectorWidth: 22,
  endEffectorHeight: 28,
};

/** Vacuum gripper robot, with its rotating, extending arm and its reach ring. */
export const vacuumGripper: ElementDrawing = {
  width: 257,
  length: 186,

  parts: id => [
    woodBase(id, 257, 186),
    group(`plasticBase${id}`, rect(257, 186, 0, 0, { class: 'plasticBase' })),
    group(`electronicCard${id}`, rect(86, 126, 0, 60, { class: 'electronicCard' })),
    axisCross(`vgrRotationAxis${id}`, VGR_ARM.pivotX, VGR_ARM.pivotY),
    group(`vgrArmAccessZone${id}`,
      el('circle', { r: VGR_ARM.maxLength, cx: VGR_ARM.pivotX, cy: VGR_ARM.pivotY, class: 'accessZone', mask: `url(#vgrArmMask${id})` })),
    el('g', { id: `vgrArmRotation${id}`, transform: `rotate(0 ${VGR_ARM.pivotX} ${VGR_ARM.pivotY})` },
      rect(VGR_ARM.minLength, VGR_ARM.thickness, VGR_ARM.pivotX, VGR_ARM.pivotY - VGR_ARM.thickness / 2,
        { id: `vgrArmBody${id}`, class: 'movingPart', fill: 'dimgray', stroke: '#4b5563', 'stroke-width': 2 }),
      rect(VGR_ARM.endEffectorWidth, VGR_ARM.endEffectorHeight,
        VGR_ARM.pivotX + VGR_ARM.minLength - VGR_ARM.endEffectorWidth, VGR_ARM.pivotY - VGR_ARM.endEffectorHeight / 2,
        { id: `vgrEndEffector${id}`, class: 'movingPart', fill: '#6b7280', stroke: '#4b5563', 'stroke-width': 2 })),
  ],

  /** Reach ring, without the inner disc and the quarter behind the robot. */
  defs: id => {
    const { pivotX: x, pivotY: y, minLength, maxLength } = VGR_ARM;
    return [
      el('mask', { id: `vgrArmMask${id}` },
        rect(maxLength * 2, maxLength * 5, x - maxLength, y - maxLength, { fill: 'white' }),
        el('circle', { r: minLength, cx: x, cy: y, fill: 'black' }),
        el('polygon', { points: `${x},${y} ${x + maxLength},${y} ${x + maxLength},${y + maxLength}`, fill: 'black' })),
    ];
  },
};
