/**
 * @mbdo/machines-svg-map: 2D map of the Fischertechnik factory machines, generated from a JSON layout and animated
 * from the raw controller attributes. Framework agnostic, no runtime dependency; the generator also runs in Node
 * (see `@mbdo/machines-svg-map/node` for file helpers and the `machines-svg-map` CLI).
 */
export {
  parseLayout,
  LayoutValidationError,
  MACHINE_KINDS,
  COMPONENT_KINDS,
  CUSTOM_SIZE_COMPONENT_KINDS,
} from './layout/FactoryLayout.js';
export type {
  FactoryLayout,
  LayoutElement,
  MachinePosition,
  ComponentPosition,
  CustomSizeComponentPosition,
  ArmAnchor,
  MachineKind,
  ComponentKind,
  CustomSizeComponentKind,
  ElementType,
  ParsedLayout,
} from './layout/FactoryLayout.js';

export { generateSvg } from './generator/generateSvg.js';
export type { GenerateOptions } from './generator/generateSvg.js';
export { ArmCalibration, computeArmCalibrations } from './generator/ArmCalibration.js';
export type { ResolvedArmAnchor } from './generator/ArmCalibration.js';
export { LEGEND_COLORS } from './generator/styles.js';

export { ShopfloorView } from './runtime/ShopfloorView.js';
export type { MountOptions, TokenState } from './runtime/ShopfloorView.js';
export { MachineModel } from './runtime/models/MachineModel.js';
export { ConveyorBeltModel } from './runtime/models/ConveyorBeltModel.js';
export { HighBayWarehouseModel } from './runtime/models/HighBayWarehouseModel.js';
export { SortingLineModel } from './runtime/models/SortingLineModel.js';
export { MultiProcessingStationModel } from './runtime/models/MultiProcessingStationModel.js';
export { VacuumGripperModel } from './runtime/models/VacuumGripperModel.js';
