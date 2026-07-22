# CatLifePet v0.8.3 Animation Asset Requirements

## Shared delivery rules

- Deliver PNG files at exactly 512 x 512 px in RGBA mode.
- The canvas background must be transparent. All four corner alpha values must be zero.
- Keep the body-bottom/foot anchor visually aligned with `cat_idle_01.png` (visible alpha bottom: y=451).
- Keep ears, tail, paws, tongue, food, and other props inside the canvas.
- The first and final pose should visually approach IDLE without requiring an exact duplicate.
- Do not bake shadows onto an opaque background.
- Use two-digit continuous numbering beginning at `01`; do not omit frame numbers.
- Intentional duplicate hold frames must be identified in the delivery notes.
- Place candidates in the matching folder under `design-source/v0.8.3/`. Only reviewed assets move to `drawable-nodpi`.

## LICKING

Naming: `cat_licking_01.png` through approximately `cat_licking_10.png`.

Required poses: IDLE approach, front paw lift, head lowering, mouth/tongue approaching paw, at least two visibly different lick poses, head lift, paw lowering, IDLE recovery. The paw, head, mouth, and tongue must change as artwork; whole-body transforms do not satisfy these poses.

Timing target: ONCE, 1.8 to 2.6 seconds. Lift may be quick, licking should hold slightly, recovery should be slower.

## YAWNING

Naming: `cat_yawning_01.png` through `cat_yawning_08.png` or `cat_yawning_10.png`.

Required poses: IDLE approach, narrowing eyes, slight head lift, mouth opening, clearly maximum open-mouth yawn, mouth closing, head recovery, IDLE recovery. The mouth and face must be redrawn; stretching-only body transforms are not a finished yawn.

Timing target: ONCE, 1.5 to 2.3 seconds, with a readable hold near the maximum yawn.

## CUDDLE

Naming: `cat_cuddle_01.png` through approximately `cat_cuddle_10.png`.

Required poses: neutral approach, slight body lean toward the user-facing side, cheek/head nuzzle, gentle closed-eye or soft expression, short affectionate hold, release, IDLE recovery. Keep the motion feline; no standing human-style hug.

Timing target: ONCE, 1.4 to 2.2 seconds. The direction should work at both screen edges or include reviewed left/right variants before integration.

## CURIOUS

Naming: `cat_curious_01.png` through approximately `cat_curious_08.png`.

Required poses: neutral approach, head/ear lift, directional head tilt, slight forward attention, observation hold, return. The expression should read as inquisitive rather than excited so it remains compatible with calm dialogue.

Timing target: ONCE, 1.2 to 2.0 seconds.

## Review gate

Run `validate_animation_assets.py --include-legacy-yawn --output validation/animation-validation.txt`, build contact sheets, inspect them at full size, preview each candidate 20 times on Pixel 6 at 80/120/160dp and both edges, then run interruption tests. A resource is not READY until all of these steps pass.
