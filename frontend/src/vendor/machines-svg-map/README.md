# machines-svg-map (copy)

Temporary copy of the browser part of `@mbdo/machines-svg-map` (`edt-edu/machines-svg-map/src`, commit `5c16340`),
until the library is packaged. Imported as `@mbdo/machines-svg-map` through the `paths` alias of
`frontend/tsconfig.json`, so the imports stay the same once the package replaces it.

Not copied: `cli.ts` and `node/` (Node only), the tests (they stay in the library).

## Adaptations to upstream

Marked "Flexible-replanning adaptation" in the code. Each one replaces a change made to the former frontend models or
backend display classes (`telemetry/state`) after the library was written:

| File | Adaptation | From |
|---|---|---|
| `generator/ArmCalibration.ts` | `MAX_ROTATION_COUNTS` (2990); the rotation counts are clamped to `[0, MAX_ROTATION_COUNTS]`, and below the first anchor the angle goes linearly to 0 degrees at 0 counts (it continued with the average slope) | `c67087c` (`VacuumGripperArmCalibration.java`) |
| `runtime/models/MachineModel.ts` | `failedSince`, `isStopped`: a machine the application found failed is drawn as not operational, its popup says "Failed at" | `62ab19d` (`GenericMachine.model.ts`) |
| `runtime/models/ConveyorBeltModel.ts` | the belt stops moving when the machine is stopped (`isStopped`, was `!isOperational`) | `62ab19d` (`ConveyorBelt.model.ts`) |
| `runtime/ShopfloorView.ts` | `setFailedMachines(Map<machineId, since>)` | `62ab19d` (layout component) |
| `runtime/ShopfloorView.ts` | `setTokens(tokens)`: several tokens, `TokenState.number` (drawn in it) and `present` (false: faded trace of the last known location); tokens on the same element are spread apart. `setToken` is kept, for one token | `73d6cb1` (layout component, multi-token) |

A token is now drawn as a `<g class="shopfloorToken">` (its circle, its number), `id="shopfloorToken"` for a token
without a number: the library test `view.test.ts` "draws the token on its location" reads the circle inside it.

**Tests:** `upstream/adaptations.test.ts` (6 tests) is written for the library's `test/` folder; it is excluded from the
frontend's TypeScript project. Run against a copy of the library with these sources: the library's 40 tests (with the
change above) and these 6 pass.

## Switching to the package

1. Upstream the adaptations above into `edt-edu/machines-svg-map`, with `upstream/adaptations.test.ts` and the
   `view.test.ts` change, and package it.
2. Add the dependency to `frontend/package.json`, remove the `@mbdo/machines-svg-map` path from
   `frontend/tsconfig.json`, delete this folder.
