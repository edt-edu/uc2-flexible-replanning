/**
 * The Flexible-replanning adaptations of the copy (see ../README.md), for the library's test/ folder once they are
 * upstreamed (the imports are the library's).
 */
import { strict as assert } from 'node:assert';
import { after, before, beforeEach, describe, it } from 'node:test';

import { Window } from 'happy-dom';

import { computeArmCalibrations, MAX_ROTATION_COUNTS } from '../src/generator/ArmCalibration.js';
import { ShopfloorView } from '../src/runtime/ShopfloorView.js';
import { loadLayout, RENNES_LAYOUT } from './helpers.js';

const layout = loadLayout(RENNES_LAYOUT);
const calibrations = computeArmCalibrations(layout);
const VGRS = ['VGRSystemModel_01', 'VGRSystemModel_02'];

describe('VGR arm on the rotation axis', () => {
  it('is at 0 degrees at 0 rotation counts, and below', () => {
    for (const vgr of VGRS) {
      assert.ok(Math.abs(calibrations[vgr].angle(0)) < 1e-9, vgr);
      assert.ok(Math.abs(calibrations[vgr].angle(-100)) < 1e-9, `${vgr}: below the axis range`);
    }
  });

  it('stops turning at the end of the axis', () => {
    for (const vgr of VGRS) {
      const atMax = calibrations[vgr].angle(MAX_ROTATION_COUNTS);
      assert.equal(calibrations[vgr].angle(4000), atMax, vgr);
      assert.ok(Math.abs(atMax) > Math.abs(calibrations[vgr].angle(2900)), `${vgr}: still turning before the end`);
    }
  });

  it('is still exact on each anchor', () => {
    for (const vgr of VGRS) {
      for (const anchor of calibrations[vgr].anchors) {
        assert.ok(Math.abs(calibrations[vgr].angle(anchor.rotationCounts) - anchor.angle) < 1e-9, `${vgr} ${anchor.name}`);
      }
    }
  });
});

describe('ShopfloorView adaptations', () => {
  let window: Window;
  let container: HTMLElement;
  let view: ShopfloorView;
  const byId = (id: string) => container.querySelector(`[id="${id}"]`);

  before(() => {
    window = new Window();
    Object.assign(globalThis, { document: window.document, DOMParser: window.DOMParser });
  });

  after(async () => {
    await window.happyDOM.close();
  });

  beforeEach(() => {
    container = window.document.createElement('div') as unknown as HTMLElement;
    view = ShopfloorView.mount(container, layout);
  });

  it('draws a failed machine as not operational, with when it failed, until it is no longer failed', () => {
    const since = new Date('2026-10-15T08:00:00Z');
    view.setFailedMachines(new Map([['CBSystemModel', since]]));
    view.flush();
    assert.ok(byId('CBSystemModel')!.classList.contains('machineNotOperational'));
    assert.ok(byId('popupCBSystemModel')!.classList.contains('machinePopupVisible'));
    assert.match(byId('popupCBSystemModel')!.querySelector('.machinePopupTime')!.textContent!, /^Failed at: /);
    assert.equal(view.machineState('CBSystemModel')!['failedSince'], since);

    view.setFailedMachines(new Map());
    view.flush();
    assert.ok(!byId('CBSystemModel')!.classList.contains('machineNotOperational'));
  });

  it('finds a failed machine by its telemetry id', () => {
    view.setFailedMachines(new Map([['HBSystemModel', new Date()]]));
    view.flush();
    assert.ok(byId('HBWSystemModel')!.classList.contains('machineNotOperational'));
  });

  it('draws every token, numbered, spread apart on the same location, a trace hollow', () => {
    view.setTokens([
      { location: 'cb-feed', color: 'RED', number: 1 },
      { location: 'cb-feed', color: 'BLUE', number: 2, present: false },
      { location: null, number: 3 },
    ]);
    const first = byId('shopfloorToken1')!;
    const second = byId('shopfloorToken2')!;
    assert.equal(byId('shopfloorToken3'), null, 'no location: not drawn');
    assert.equal(first.querySelector('text')!.textContent, '1');
    assert.equal(Number(second.querySelector('circle')!.getAttribute('cx'))
      - Number(first.querySelector('circle')!.getAttribute('cx')), 26);
    assert.equal(second.querySelector('circle')!.getAttribute('fill'), 'none');
    assert.equal(second.getAttribute('opacity'), '0.6');

    view.setTokens([]);
    assert.equal(container.querySelectorAll('.shopfloorToken').length, 0);
  });
});
