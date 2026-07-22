# CatLifePet Design Assets

## v1.0 release contract

- Runtime pet artwork stays in `app/src/main/res/drawable-nodpi/` as 512 x 512 RGBA PNG files.
- Every animation frame must keep at least 8 px of transparent canvas on every side.
- Frames in one animation may move, but their bottom anchor may drift by no more than 8 px.
- `PetAssetContractTest` checks dimensions, alpha-safe bounds, frame count, and bottom anchors during the unit-test build.
- `cat_idle.png` is also the source artwork inside the launcher and splash wrappers. Do not replace launcher XML files with a full-bleed bitmap.
- `ic_notification_cat.xml` is a monochrome Android status-bar icon and must remain a solid white silhouette.
- Adaptive launcher resources include a safe foreground inset and Android 13 monochrome layer.
- Validate 80dp, 120dp, and 160dp pets on both screen edges after changing any artwork.

当前桌宠状态使用以下 PNG：

- `cat_idle.png`
- `cat_happy.png`
- `cat_eating.png`
- `cat_drinking.png`
- `cat_sleeping.png`
- `cat_stretching.png`
- `cat_dragging.png`

资源要求：

- 512 x 512 px
- 透明背景
- 所有状态画布大小一致
- 小猫中心点一致
- 不要裁切耳朵、尾巴、道具
- 文件名必须和项目资源名一致
- 替换到 `app/src/main/res/drawable-nodpi/` 后重新 build 即可生效

当前 7 张 512 x 512 透明状态 PNG 已接入。状态资源统一由 `CatAnimationManager` 管理，后续可以在该类内部升级为同状态 PNG 多帧序列，而不需要修改提醒、点击和拖动业务逻辑。

2026-07-15 已将原素材中与画布边缘连通的黑色背景转换为透明 Alpha，并保留主体内部的黑色眼睛与轮廓。处理前原图备份位于项目根目录 `design-source/opaque-background/`。

## IDLE sequence

IDLE 当前循环播放 `cat_idle_01.png` 到 `cat_idle_06.png`，其他状态仍使用单张 PNG。序列帧由 `design-source/generate_idle_frames.py` 基于 `cat_idle.png` 生成，保持 512 x 512 RGBA、透明背景和底部中心锚点；重新生成前脚本会把已有帧备份到 `design-source/idle-animation-backup/`。

## v0.2 animation states

| 状态 | 帧数 | 播放模式 | 帧时长 |
| --- | ---: | --- | --- |
| IDLE | 6 | LOOP | 180 / 160 / 180 / 160 / 180 / 180 ms |
| HAPPY | 6 | ONCE | 120 / 120 / 140 / 160 / 140 / 200 ms |
| EATING | 8 | LOOP | 140 / 120 / 140 / 120 / 140 / 120 / 140 / 220 ms |
| DRINKING | 8 | LOOP | 160 / 140 / 140 / 180 / 160 / 140 / 160 / 240 ms |
| SLEEPING | 6 | LOOP | 350 / 350 / 450 / 350 / 350 / 500 ms |
| STRETCHING | 6 | ONCE | 180 / 180 / 220 / 250 / 350 / 300 ms |
| DRAGGING | 4 | LOOP | 180 / 180 / 180 / 180 ms |

动画是基于单张静态 PNG 的轻量程序化变换。替换任意状态的原始同名 PNG 后，运行 `design-source/generate_animation_frames.py` 即可重新生成技术版序列帧；未来如果设计师提供真正不同姿势的逐帧原画，可以直接替换对应的 `cat_xxx_01.png`、`cat_xxx_02.png` 等文件，`CatAnimationManager` 无需修改。
