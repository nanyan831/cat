# LICKING Animation Asset Brief

## Goal

Create a clearly readable self-grooming animation for the existing `LICKING` state. The cat remains seated, raises one front paw, lowers its head, visibly touches or approaches the paw with its tongue for one or two licks, then returns to IDLE.

This delivery is blocked until genuinely different drawn poses exist. Whole-character scaling, translation, compression, stretching, HAPPY reuse, or IDLE transforms are not acceptable final artwork.

## Delivery location

Place unreviewed frames in:

`design-source/v0.8.3/licking/incoming/`

Do not place candidates directly in `drawable-nodpi`. After validation and visual approval, copy the accepted set to `approved/`; only then may engineering promote the same files to Android resources.

## Canvas and style

- PNG, 512 x 512 px, RGBA, transparent background.
- Match the existing CatLifePet line weight, palette, face, body proportions, and soft shading.
- Use `app/src/main/res/drawable-nodpi/cat_idle_01.png` as the visual anchor reference.
- Keep the body bottom and supporting paw near the IDLE bottomY. Aim for <= 4 px deviation on ordinary poses.
- Keep ears, tail, raised paw, and tongue inside the canvas. Canvas corners must remain fully transparent.
- Do not shift the whole cat left/right between frames. Head and paw movement may alter visible centerX naturally.

## Ten-frame pose plan

1. `cat_licking_01.png`, 160 ms: seated pose close to IDLE.
2. `cat_licking_02.png`, 150 ms: one front paw begins to lift.
3. `cat_licking_03.png`, 180 ms: paw raised; head starts turning toward it.
4. `cat_licking_04.png`, 180 ms: head lowers toward the paw.
5. `cat_licking_05.png`, 260 ms: first clear tongue/paw contact.
6. `cat_licking_06.png`, 260 ms: visibly different second lick pose.
7. `cat_licking_07.png`, 180 ms: head begins to rise.
8. `cat_licking_08.png`, 170 ms: paw begins to lower.
9. `cat_licking_09.png`, 160 ms: seated posture is nearly restored.
10. `cat_licking_10.png`, 180 ms: final pose approaches IDLE.

Total target timing: 1,880 ms. Up to 12 frames and 1.8-2.5 seconds are acceptable when needed for genuine pose continuity. Do not add duplicate frames merely to reach a count.

## Direction and semantics

Prefer a front paw and head direction that remains readable at either screen edge. The action must immediately read as licking/grooming, not waving, greeting, stretching, bouncing, hugging, or a generic happy pose.

## Validation and promotion

Run from the project root:

```powershell
design-source\v0.8.3\licking\validate_licking.ps1
```

The script validates incoming files and creates reports under `licking/validation/` and `qa/v0.8.3.1/`. A failed or missing set must remain in `incoming/` or be copied to `rejected/` with its reason added to `rejection-reasons.txt`. The script never copies files into `approved/` or Android resources.

Only after automated validation, contact-sheet semantic review, anchor review, transparent-background review, and Pixel 6 playback approval may `assetStatus` become `READY` and the files be promoted.
