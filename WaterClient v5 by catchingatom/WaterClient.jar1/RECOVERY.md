# WaterClient — source recovery notes

This project was reconstructed from `WaterClient.jar`, an obfuscated release build, after the
original source was lost. It builds cleanly:

```bash
./gradlew build
```

Output: `build/libs/WaterClient-1.0.0.jar`

---

## What the obfuscator did (and didn't do)

The jar was processed by a name-mangling obfuscator plus a string-encryption pass. Crucially it
was **class-name-only**: field names and the public/override method surface were left untouched.

| Layer | Status |
|---|---|
| Field names | **Fully preserved** — `settings`, `enabled`, `bind`, `scanRadius`, `fillAlpha`, … |
| Public / override methods | **Preserved** — `onTick`, `onEnable`, `onPacketSend`, `getSettings`, … |
| Private helper methods | **Recovered by hand** — all 981 renamed from `gN`/`qz`/`mK` to real names |
| Class names | **Lost** — reconstructed (see below) |
| Package layout | **Recovered** from leaked switch-map fields |
| String literals | **Recovered** — decrypted from the lookup tables |
| Generic type signatures | **Stripped** — reconstructed where the compiler required it |
| `SourceFile` attributes | Stripped |

## How the names were recovered

Class names were **not** guessed blindly. Sources used, in order of confidence:

1. **Leaked original packages.** Two synthetic switch-map fields survived with fully-qualified
   names baked in: `$SwitchMap$com$water$module$Category` and
   `$SwitchMap$com$water$module$modules$client$Hud$HudElement`. These established the original
   package layout (`com.water.module.modules.<category>`), which this tree reproduces.
2. **Module names + categories from bytecode.** Every module calls
   `super("<name>", Category.<CAT>)` in its constructor. Decrypting the string tables and reading
   the constructor bytecode recovered **69 of 72 module names with their real categories** —
   `AutoCrystal`, `ShieldBreaker`, `StorageESP`, `SpotifyHUD`, `TabDetector`, and so on.
3. **Shader names.** The eight render-pipeline classes were identified by the shader they load
   (`rectangle_fragment` → `RectanglePipeline`, `liquidglass_vertex` → `LiquidGlassPipeline`, …).
4. **Member-signature inference** for the remaining ~90 support classes (settings types, screens,
   records, util). These names are *descriptive, not original* — e.g. `Render2D`, `HealthData`,
   `ConfigFileManager`. Rename freely; they carry no external contract.

The full obfuscated→recovered map is in [`tools/rename_map.json`](tools/rename_map.json).

## Things you should know before shipping

- **`com.water.auth.NativeAuth` / `NativeLoader` are missing.** They are referenced only via
  `Class.forName(...)` from `util/AuthBridge.java`, so the project compiles, but the integrity
  check in `util/IntegrityCheck.java` will take its failure path at runtime. Those classes were
  never in this jar — they must have been a separate artifact. Wire them back up or delete both
  classes.
- **Two static initialisers were stripped by the obfuscator and are reconstructed guesses.**
  Both are commented `RECOVERY NOTE` in-source:
  - `module/modules/donut/StaffDetector.java` — `RANK_KEYWORDS` / `KNOWN_STAFF`. These fields
    were `null` in the released jar, so staff detection would have NPE'd. Contents are rebuilt
    from leftover string constants; **the exact split and ordering are unverified**.
  - `module/modules/misc/NameTags.java` — heart sprite `Identifier`s and the colour-code
    `Pattern`. These are taken verbatim from surviving constants and are safe.
- **`module/modules/client/Water.java`** — the large `iY(String)` key-name→GLFW-code switch
  defeated Vineflower (stack overflow); that one method was recovered with CFR instead. Behaviour
  is identical, formatting differs slightly from the rest of the tree.
- **`com/water/S.java` and `com/water/runtime/R.java` were dropped.** They were the string
  decryptors; all 46 call sites are now plain literals. Decrypted tables are archived in
  `tools/*.strings.txt`.
- **`water.mixins.json` no longer declares a `refmap`.** Loom now remaps mixin annotations
  statically (`Fabric-Loom-Mixin-Remap-Type: static`), so the refmap is neither generated nor
  needed. All 31 mixins are present and remap correctly.

## Build environment

Recovered from the original jar's `META-INF/MANIFEST.MF`:

- Minecraft 1.21.11, Yarn `1.21.11+build.6`
- Fabric Loader 0.18.4, Fabric API `0.141.6+1.21.11`
- Loom 1.14.10, Gradle 9.2.1, Java 21

## Reproducing the recovery

`tools/pipeline.sh` re-runs the whole thing from `WaterClient.jar`: build mappings → remap
intermediary→Yarn *and* obfuscated→named in one tiny-remapper pass → decompile → inline strings.
It is kept for reference; you should not need it again.

## Readability pass

Three mechanical passes were applied on top of the recovery, each verified by a full rebuild:

- **981 method names** renamed from the obfuscator's `qz`/`mK`/`rF` to real names, derived from
  each method's body. Applied *type-aware*: a name is only rewritten where the receiver is
  provably the declaring class, so Minecraft's own API can never be hit.
- **3,635 reference-typed parameters** renamed from `var1`/`var2` to names from their type
  (`DrawContext context`, `BlockPos pos`, `ItemStack stack`, `PlayerEntity player`, …).
- **3,493 locals** un-jammed from Vineflower's style (`blockpos` → `blockPos`).
- **2,029 primitive parameters** named by reading each method's body — `x`, `y`, `width`,
  `height`, `radius`, `color`, `alpha`, `mouseX`, `mouseY`, `tickDelta`, `chunkKey`, …
- Two classes I had **misnamed** were corrected: `ConfigShareUtil` → `SpotifyApi` (it only ever
  called `api.spotify.com`) and `WebhookUtil` → `RoleTextUtil` (it contains no webhook code).

### What is still not pretty

About 1,100 primitive parameters and locals keep Vineflower's names (`int i, j, k`, `float f1`,
`double d0`, and `varN` on a few hundred low-traffic methods). Their original names are genuinely
unrecoverable — nothing in a compiled jar records them — and the remaining ones are the cases
where guessing would risk mislabelling a coordinate or a colour. They were left alone on purpose.
Comments and javadoc are likewise gone for good; the obfuscator stripped them.

## How the rebuild was verified

Renaming 4,500+ identifiers by script is only safe if you can prove you did not change
behaviour. Three checks were run against the original bytecode (kept as ground truth):

1. **Lost members** — every method/field in the original is present in the rebuild.
2. **External call equivalence** — the set of Minecraft/JDK methods the rebuild calls is
   identical to the original's, so no rename hijacked an MC API method.
3. **Internal call-graph equivalence** — after applying the rename map, **0** call targets are
   unmatched between the original and the rebuild.

An earlier, naive global rename *did* break this: it rewrote `Click.x()`/`Click.y()` into
`getGridHeight()`/`getVisibleRows()` because the GUI classes had methods named `x` and `y`.
Check 2 is what catches that class of mistake.

## Two modules were already broken in the jar you were given

Neither was caused by the recovery — both were verified defective in the original bytecode:

- **Region Map** — the module is fully implemented (~200 lines of map drawing), but nothing ever
  called `render(...)`. Confirmed: in the original jar, `ModuleManager` only *constructs* it and
  no class anywhere invokes `RegionMap.render`. **Fixed** by wiring it into the HUD render pass
  in `util/KeybindHandler.java` (marked `RECOVERY FIX`).
- **Hitbox** — an empty stub. It registered an "Expand" setting and nothing else; no code read
  the setting. **Implemented** in `mixin/EntityMixin.java` (marked `RECOVERY NOTE`) as a
  targeting-margin expansion. This is new code, not recovered, and is a no-op at the default
  setting of 1.0.

If other modules misbehave, the same technique will find them — see `tools/` for the scripts.
