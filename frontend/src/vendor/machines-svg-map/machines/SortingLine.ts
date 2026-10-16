import { ElementDrawing, group, rect, woodBase } from './shapes.js';

/** Sorting line with color sensor: its conveyor, 3 ejectors / slides and 5 light barriers. */
export const sortingLine: ElementDrawing = {
  width: 440,
  length: 310,

  parts: id => [
    woodBase(id, 440, 310),
    group(`plasticBase${id}`, rect(380, 258, 0, 12, { class: 'plasticBase' })),
    group(`conveyorZone${id}`, rect(400, 30, -10, 105, { class: 'conveyorZone' })),
    group(`slide${id}`,
      rect(30, 86, 210, 135, { class: 'slide' }),
      rect(30, 86, 270, 135, { class: 'slide' }),
      rect(30, 86, 333, 135, { class: 'slide' })),
    group(`electronicCard${id}`, rect(126, 86, 30, 180, { class: 'electronicCard' })),
    group(`ejector${id}`,
      rect(15, 93, 220, 12, { class: 'ejector' }),
      rect(15, 93, 280, 12, { class: 'ejector' }),
      rect(15, 93, 338, 12, { class: 'ejector' })),
    group(`sensorsGroup${id}`,
      rect(15, 72, 10, 85, { id: `sortingLineSensInputLightBarrier${id}`, class: 'lightBarrier' }),
      rect(15, 72, 195, 85, { id: `sortingLineSensMiddleLightBarrier${id}`, class: 'lightBarrier' }),
      rect(68, 15, 193, 200, { id: `sortingLineSensWhiteLightBarrier${id}`, class: 'lightBarrier' }),
      rect(68, 15, 315, 200, { id: `sortingLineSensBlueLightBarrier${id}`, class: 'lightBarrier' }),
      rect(68, 15, 252, 185, { id: `sortingLineSensRedLightBarrier${id}`, class: 'lightBarrier' }),
      rect(110, 60, 48, 90, { id: `colorSensor${id}`, class: 'colorLightBarrier' })),
  ],
};
