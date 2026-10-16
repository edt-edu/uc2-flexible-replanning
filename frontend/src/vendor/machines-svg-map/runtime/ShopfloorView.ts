import { ArmCalibration, computeArmCalibrations } from '../generator/ArmCalibration.js';
import { buildScene, GenerateOptions } from '../generator/generateSvg.js';
import { LEGEND_COLORS } from '../generator/styles.js';
import { FactoryLayout, isMachine, MachineKind } from '../layout/FactoryLayout.js';
import { serialize } from '../svg/SvgNode.js';
import { ConveyorBeltModel } from './models/ConveyorBeltModel.js';
import { HighBayWarehouseModel } from './models/HighBayWarehouseModel.js';
import { byId, MachineModel } from './models/MachineModel.js';
import { MultiProcessingStationModel } from './models/MultiProcessingStationModel.js';
import { SortingLineModel } from './models/SortingLineModel.js';
import { VacuumGripperModel } from './models/VacuumGripperModel.js';

/** Token (workpiece) to draw on the map. */
export interface TokenState {
  /** Location, as a key of the layout's `tokenLocations`; null when there is no token. */
  location: string | null;
  /** WHITE, RED, BLUE or any CSS color; unknown (gray) when null. */
  color?: string | null;
  /** Drawn with a dashed red outline, e.g. last known location before an anomaly. */
  anomaly?: boolean;
  /** Tooltip of the token. */
  title?: string;
  /** Number drawn in the token, e.g. its order on the line. Flexible-replanning adaptation (see ../README.md). */
  number?: number;
  /**
   * False: a trace of the token's last known location (faded, hollow), e.g. the sensor no longer sees it. Default true.
   * Flexible-replanning adaptation (see ../README.md).
   */
  present?: boolean;
}

const TOKEN_FILLS: Record<string, string> = {
  WHITE: LEGEND_COLORS.tokenWhite,
  RED: LEGEND_COLORS.tokenRed,
  BLUE: LEGEND_COLORS.tokenBlue,
};
const TOKEN_ELEMENT_CLASS = 'shopfloorToken';
/** Tokens drawn on the same element (e.g. while one leaves and the next arrives) are spread apart by this much. */
const TOKEN_SPREAD = 26;
const SVG_NS = 'http://www.w3.org/2000/svg';

const MODEL_BY_KIND: Record<MachineKind, (id: string, calibration?: ArmCalibration) => MachineModel> = {
  CB: id => new ConveyorBeltModel(id),
  HBW: id => new HighBayWarehouseModel(id),
  SLC: id => new SortingLineModel(id),
  MPO: id => new MultiProcessingStationModel(id),
  VGR: (id, calibration) => new VacuumGripperModel(id, calibration),
};

export interface MountOptions extends GenerateOptions {
  /** Makes the SVG fill its container, keeping its aspect ratio. Default true. */
  fit?: boolean;
}

/**
 * Live view of a factory layout: draws the raw controller attributes of each machine on its SVG.
 *
 * Framework agnostic: create it with {@link ShopfloorView.mount} (generates the SVG in a container) or
 * {@link ShopfloorView.attach} (SVG already in the page, generated from the same layout), feed it with
 * {@link applyAttribute}, and {@link destroy} it with the component.
 */
export class ShopfloorView {
  private readonly models = new Map<string, MachineModel>();
  /** Telemetry machine id -> machine id */
  private readonly machineIdByTelemetryId = new Map<string, string>();
  private readonly groups = new Map<string, SVGGElement>();
  private readonly dirty = new Set<string>();
  private frame: number | null = null;
  private tokens: TokenState[] = [];

  /** Generates the SVG of the layout into `container` (replacing its content) and returns its view. */
  static mount(container: Element, layout: FactoryLayout, options: MountOptions = {}): ShopfloorView {
    const scene = buildScene(layout, options);
    // parsed as XML, as the SVG file would be (the HTML parser of innerHTML is not reliable on SVG <style>)
    const parsed = new DOMParser().parseFromString(serialize(scene), 'image/svg+xml').documentElement;
    const svg = container.ownerDocument.importNode(parsed, true) as unknown as SVGSVGElement;
    container.replaceChildren(svg);
    if (options.fit ?? true) {
      svg.setAttribute('width', '100%');
      svg.setAttribute('height', '100%');
      svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    }
    return new ShopfloorView(svg, layout, computeArmCalibrations(layout, scene), container);
  }

  /** Creates the view of an SVG already in the page, generated from this layout. */
  static attach(svg: SVGSVGElement, layout: FactoryLayout): ShopfloorView {
    return new ShopfloorView(svg, layout, computeArmCalibrations(layout));
  }

  private constructor(
    readonly svg: SVGSVGElement,
    readonly layout: FactoryLayout,
    calibrations: Record<string, ArmCalibration>,
    private readonly container?: Element,
  ) {
    for (const element of layout.positions) {
      if (!isMachine(element) || this.models.has(element.id)) {
        continue;
      }
      this.models.set(element.id, MODEL_BY_KIND[element.kind](element.id, calibrations[element.id]));
      this.machineIdByTelemetryId.set(element.telemetryId ?? element.id, element.id);
      const group = byId<SVGGElement>(svg, element.id);
      if (group) {
        this.groups.set(element.id, group);
      } else {
        console.warn(`[ShopfloorView] No SVG group for machine '${element.id}'`);
      }
    }
    this.models.forEach((_, id) => this.dirty.add(id));
    this.flush();
  }

  /**
   * Applies a raw controller attribute of a machine, e.g. (`SLSystemModel`, `sortingLineSensInputLightBarrier`,
   * false). The machine is redrawn on the next animation frame (see {@link flush}).
   *
   * @param machineId telemetry machine id (`telemetryId` of the layout) or layout machine id
   * @returns false when no machine of the layout has this id
   */
  applyAttribute(machineId: string, attribute: string, value: unknown, timestamp?: Date | string | number): boolean {
    const id = this.machineIdByTelemetryId.get(machineId.trim()) ?? machineId.trim();
    const model = this.models.get(id);
    if (!model) {
      return false;
    }
    model.applyAttribute(attribute, value, timestamp === undefined ? new Date() : new Date(timestamp));
    this.dirty.add(id);
    this.scheduleFlush();
    return true;
  }

  /** Draws the token at its location, or removes it (null, or no location). */
  setToken(token: TokenState | null): void {
    this.setTokens(token ? [token] : []);
  }

  /**
   * Draws each token at its location (the ones without a location are not drawn), replacing the previous ones.
   * Flexible-replanning adaptation, not in the library yet (see ../README.md).
   */
  setTokens(tokens: readonly TokenState[]): void {
    this.tokens = [...tokens];
    this.renderTokens();
  }

  /**
   * Draws as not operational the machines the application found failed (by layout or telemetry id), since when; the
   * others are drawn from their controller attributes again.
   * Flexible-replanning adaptation, not in the library yet (see ../README.md).
   */
  setFailedMachines(failed: ReadonlyMap<string, Date>): void {
    const failedSince = new Map<string, Date>();
    failed.forEach((since, machineId) => failedSince.set(this.machineIdByTelemetryId.get(machineId) ?? machineId, since));
    this.models.forEach((model, id) => {
      const since = failedSince.get(id) ?? null;
      if (model.failedSince?.getTime() !== since?.getTime()) {
        model.failedSince = since;
        this.dirty.add(id);
      }
    });
    this.scheduleFlush();
  }

  /** Current state of a machine, by layout or telemetry id. */
  machineState(machineId: string): Record<string, unknown> | undefined {
    return this.models.get(this.machineIdByTelemetryId.get(machineId) ?? machineId)?.state();
  }

  /** Redraws now the machines changed since the last frame. */
  flush(): void {
    if (this.frame !== null) {
      cancelAnimationFrame(this.frame);
      this.frame = null;
    }
    let tokenMoved = false;
    for (const id of this.dirty) {
      const group = this.groups.get(id);
      if (group) {
        this.models.get(id)!.render(group);
        // a token held by a gripper follows its arm
        tokenMoved ||= this.tokens.some(token => this.tokenAnchor(token)?.closest(`[id="${id}"]`) === group);
      }
    }
    this.dirty.clear();
    if (tokenMoved) {
      this.renderTokens();
    }
  }

  /** Stops the pending redraw and, for a mounted view, empties its container. */
  destroy(): void {
    if (this.frame !== null) {
      cancelAnimationFrame(this.frame);
      this.frame = null;
    }
    if (this.container) {
      this.container.innerHTML = '';
    }
  }

  private scheduleFlush(): void {
    if (typeof requestAnimationFrame !== 'function') {
      this.flush();
      return;
    }
    this.frame ??= requestAnimationFrame(() => {
      this.frame = null;
      this.flush();
    });
  }

  private tokenAnchor(token: TokenState): SVGGraphicsElement | null {
    const anchorId = token.location ? this.layout.tokenLocations?.[token.location] : undefined;
    return anchorId ? byId<SVGGraphicsElement>(this.svg, anchorId) : null;
  }

  private renderTokens(): void {
    this.svg.querySelectorAll(`.${TOKEN_ELEMENT_CLASS}`).forEach(element => element.remove());
    const drawnAt = new Map<string, number>();
    for (const token of this.tokens) {
      if (!token.location) {
        continue;
      }
      const index = drawnAt.get(token.location) ?? 0;
      drawnAt.set(token.location, index + 1);
      this.renderToken(token, index);
    }
  }

  /** Token drawn on its anchor, in the anchor's coordinate system, so that it follows the machine's transforms. */
  private renderToken(token: TokenState, index: number): void {
    const anchor = this.tokenAnchor(token);
    if (!anchor) {
      console.warn(`[ShopfloorView] No SVG element for token location '${token.location}'`);
      return;
    }

    const box = anchor.getBBox();
    const cx = box.x + box.width / 2 + index * TOKEN_SPREAD;
    const cy = box.y + box.height / 2;
    const present = token.present ?? true;
    const group = document.createElementNS(SVG_NS, 'g');
    group.setAttribute('class', TOKEN_ELEMENT_CLASS);
    group.setAttribute('id', token.number === undefined ? TOKEN_ELEMENT_CLASS : `${TOKEN_ELEMENT_CLASS}${token.number}`);

    const circle = document.createElementNS(SVG_NS, 'circle');
    circle.setAttribute('cx', String(cx));
    circle.setAttribute('cy', String(cy));
    circle.setAttribute('r', '12');
    if (present) {
      circle.setAttribute('fill', token.color ? TOKEN_FILLS[token.color] ?? token.color : LEGEND_COLORS.tokenUnknownColor);
      circle.setAttribute('stroke', token.anomaly ? LEGEND_COLORS.tokenAnomaly : '#111827');
      circle.setAttribute('stroke-width', token.anomaly ? '3' : '2');
      if (token.anomaly) {
        circle.setAttribute('stroke-dasharray', '4 3');
      }
    } else {
      group.setAttribute('opacity', '0.6');
      circle.setAttribute('fill', 'none');
      circle.setAttribute('stroke', token.anomaly ? LEGEND_COLORS.tokenAnomaly : '#94a3b8');
      circle.setAttribute('stroke-width', '2');
      circle.setAttribute('stroke-dasharray', '4 3');
    }
    if (token.title) {
      const title = document.createElementNS(SVG_NS, 'title');
      title.textContent = token.title;
      group.appendChild(title);
    }
    group.appendChild(circle);
    if (token.number !== undefined) {
      const label = document.createElementNS(SVG_NS, 'text');
      label.setAttribute('x', String(cx));
      label.setAttribute('y', String(cy));
      label.setAttribute('text-anchor', 'middle');
      label.setAttribute('dominant-baseline', 'central');
      label.setAttribute('font-size', '12');
      label.setAttribute('font-weight', '700');
      label.setAttribute('fill', !present ? '#64748b' : token.color === 'RED' || token.color === 'BLUE' ? '#ffffff' : '#111827');
      label.setAttribute('pointer-events', 'none');
      label.textContent = String(token.number);
      group.appendChild(label);
    }

    const isGroup = anchor.tagName.toLowerCase() === 'g';
    const anchorTransform = anchor.getAttribute('transform');
    if (anchorTransform && !isGroup) {
      group.setAttribute('transform', anchorTransform);
    }
    (isGroup ? anchor : anchor.parentNode)?.appendChild(group);
  }
}
