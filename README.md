![logo](src/main/resources/assets/continuity/neo_continuity_icon.png)

# CleanContinuity for Minecraft 1.12.2 ([EN version](#english))

[GitHub 仓库](https://github.com/Q-Engineering-Source/CleanContinuity)

## 🍝赞助

CleanContinuity 是 [NeoContinuity](https://github.com/Argon4W/NeoContinuity) 的 1.12.2 移植版. 上游 NeoContinuity 是 Argon4W 基于 PepperCode1 官方 Continuity 开发的 NeoForge 原生分支.
如果你喜欢这个 MOD 并想支持上游 NeoContinuity 的开发, 请前往 [爱发电](https://afdian.com/a/argon4w) 为狐狸买一份意面.
也十分感谢 Argon4W 制作精良的 NeoContinuity, 以及 PepperCode1 制作精良的官方 Continuity, 若想支持上游的开发, 请前往 [Buy Me a Coffee](https://buymeacoffee.com/peppercode1) 进行赞助.

## 🖥️模组介绍

CleanContinuity 是 [NeoContinuity](https://github.com/Argon4W/NeoContinuity) (NeoForge 原生分支) 的 **Minecraft 1.12.2 移植版**, 基于 Cleanroom Loader, 并将渲染部分接入 Actinium 管线. 它让旧版 Minecraft 也能使用 OptiFine 连接纹理 (CTM) 与发光纹理资源包, 无需 OptiFine; 同时还额外兼容 CTM Mod 格式的贴图驱动资源包 (含 `ctm.json` / `ctm_logic` 自定义真值表). **请勿** 向官方或 NeoContinuity 作者报告 CleanContinuity 的问题.

## ✨为什么需要这个MOD

官方 Continuity 仅有 Fabric 版本, 而 [NeoContinuity](https://github.com/Argon4W/NeoContinuity) 提供原生 NeoForge 分支. CleanContinuity 将这一分支移植到 1.12.2, 并接入 Actinium 渲染管线, 让旧版客户端也能使用连接纹理与发光纹理资源包, 无需 OptiFine.

## ⚙️工作原理

CleanContinuity 在 1.12.2 上使用 Cleanroom Loader 提供的现代 Java 与 Mixin 能力, 基于 NeoContinuity 已移除 Fabric API 依赖的代码继续适配. 它将渲染挂载点接入 Actinium 的 `BlockQuadTransformer` 地形渲染钩子, 通过资源包扫描与 `TextureStitchEvent` 将纹理注入方块图集, 并在四边形层面完成连接纹理替换与发光叠加.

## ♻️API 替换:
- Fabric 的 `emitQuads` 方法替换为 Actinium 提供的 `BlockQuadTransformer.transform` 方法.
- Fabric 的 `QuadView` 与 `MutableQuadView` 替换为 `BakedQuad` 与 `MutableQuad` 线段视图.
- Fabric 的 `MutableMesh` 替换为可复用的四边形构建器.
- Fabric 的资源加载与配置 API 替换为 Cleanroom / Forge 原生事件与 JSON 配置.

## ✨特性

- OptiFine 连接纹理: `ctm` / `glass` / `horizontal` / `bookshelf` / `vertical` / `top` / `fixed` / `random` / `repeat` / `overlay` (17 tiles).
- OptiFine 发光纹理: `_e` 后缀发光贴图, 支持方块与物品.
- CTM Mod 格式兼容: `.png.mcmeta` 的 `"ctm"` section (v1 类型) 与 `ctm.json` + `ctm_logic/*.json` 自定义真值表, 含 `proxy` 转发.
- CTM 元数据也可从 B.A.S.E / Resource Loader 的 `resources` 目录加载; 支持 `layer`、`extra.light` 和跨方块 `extra.connect_to`. 对 `_e` 贴图设置 `extra.emissive_fallback: true` 可在分层泛光时保留原图层的全亮发光.
- 内置资源包: 默认连接纹理包 (玻璃 / 砂岩 / 书架) 与玻璃板剔除修复包.

<a id="english"></a>
# CleanContinuity for Minecraft 1.12.2

Repository: [GitHub](https://github.com/Q-Engineering-Source/CleanContinuity)

## 🍝Sponsorship

CleanContinuity is a Minecraft 1.12.2 port of [NeoContinuity](https://github.com/Argon4W/NeoContinuity), the native NeoForge fork written by Argon4W based on PepperCode1's official Continuity.
Player sponsorships help support future ports and improvements. If you want to support upstream NeoContinuity, please consider sponsoring Argon4W at [爱发电](https://afdian.com/a/argon4w).
Also thanks for Argon4W for making such great NeoContinuity, and PepperCode1 for making the official Continuity. If you want to support the upstream development, Please sponsor at [Buy Me a Coffee](https://buymeacoffee.com/peppercode1).

## 🖥️MOD Description

CleanContinuity is a **Minecraft 1.12.2 port of [NeoContinuity](https://github.com/Argon4W/NeoContinuity)** (the native NeoForge fork), built on Cleanroom Loader, with rendering integrated into the Actinium pipeline. It brings OptiFine connected textures (CTM) and emissive textures to legacy Minecraft without requiring OptiFine, and additionally supports CTM Mod format texture-driven resource packs (including `ctm.json` / `ctm_logic` custom truth tables). Do **NOT** report CleanContinuity issues to the official or NeoContinuity's author.

## ✨Why need this MOD

The official Continuity is Fabric-only, while [NeoContinuity](https://github.com/Argon4W/NeoContinuity) provides a native NeoForge fork. CleanContinuity brings that codebase to 1.12.2 and integrates it with Actinium, so legacy clients can use connected and emissive texture resource packs without OptiFine.

## ⚙️How it works

On 1.12.2, CleanContinuity uses modern Java and Mixin from Cleanroom Loader, building on NeoContinuity's Fabric-API-free codebase. It connects rendering to Actinium's `BlockQuadTransformer` terrain pipeline. Resource pack scanning and `TextureStitchEvent` inject textures into the block atlas, and connected/emissive replacement happens at the quad level.

## ♻️API Replacement:
- Fabric's `emitQuads` replaced with Actinium's `BlockQuadTransformer.transform` method.
- Fabric's `QuadView` and `MutableQuadView` replaced with `BakedQuad` and `MutableQuad` views.
- Fabric's `MutableMesh` replaced with a reusable quad builder.
- Fabric's resource loading and config APIs replaced with Cleanroom/Forge native events and JSON config.

## ✨Features

- OptiFine connected textures: `ctm` / `glass` / `horizontal` / `bookshelf` / `vertical` / `top` / `fixed` / `random` / `repeat` / `overlay` (17 tiles).
- OptiFine emissive textures: `_e`-suffixed emissive textures for blocks and items.
- CTM Mod format compatibility: `"ctm"` section of `.png.mcmeta` (v1 types) and `ctm.json` + `ctm_logic/*.json` custom truth tables, including `proxy` forwarding.
- CTM metadata also loads from B.A.S.E / Resource Loader `resources` roots, with `layer`, `extra.light`, and cross-block `extra.connect_to`. Set `extra.emissive_fallback: true` on an `_e` texture to keep its full-bright overlay in the original layer alongside routed Bloom.
- Built-in resource packs: default connected textures pack (glass / sandstone / bookshelves) and glass pane culling fix pack.
