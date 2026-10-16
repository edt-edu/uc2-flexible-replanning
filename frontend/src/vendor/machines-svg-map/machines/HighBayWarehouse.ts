import { ElementDrawing, group, rect, woodBase } from './shapes.js';

/** High bay warehouse, with 3 storage places and its input / output light barriers. */
export const highBayWarehouse: ElementDrawing = {
  width: 690,
  length: 440,

  parts: id => [
    woodBase(id, 690, 440),
    group(`plasticBase${id}`, rect(576, 258, 57, 84, { class: 'plasticBase' })),
    group(`conveyorZone${id}`, rect(31, 132, 144, 215, { class: 'conveyorZone' })),
    group(`electronicCard${id}`, rect(86, 126, 546, 215, { class: 'electronicCard' })),
    group(`storage${id}`,
      rect(30, 30, 262, 218, { class: 'storage' }),
      rect(30, 30, 352, 218, { class: 'storage' }),
      rect(30, 30, 442, 218, { class: 'storage' })),
    group(`sensorsGroup${id}`,
      rect(72, 15, 125, 325, { id: `inputBarrier${id}`, class: 'lightBarrier' }),
      rect(72, 15, 125, 222, { id: `outputBarrier${id}`, class: 'lightBarrier' })),
    group(`armAccessZone${id}`, rect(340, 70, 144, 175, { class: 'accessZone' })),
  ],
};
