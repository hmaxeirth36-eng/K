# Security audit — WaterClient.jar

Full review of the jar you were given, done on the recovered sources **and** on the raw bytecode
(so nothing could hide in code the decompiler dropped).

## Verdict

**No RAT, no info-stealer, no IP grabber.** Nothing in this jar exfiltrates your data.

## What was checked

| Check | Result |
|---|---|
| Minecraft session token / `getAccessToken()` | **Not present anywhere** — the #1 stealer target is never touched |
| `launcher_accounts.json`, `.minecraft` creds, browser cookies, Discord leveldb | No access, no references |
| `Runtime.exec` / arbitrary command execution | None |
| `URLClassLoader`, `defineClass`, remote code loading | None |
| Nested `.exe` / `.dll` / `.jar` payloads in the archive | None — only classes, fonts, shaders, PNGs, JSON |
| Hardcoded exfil endpoints / IPs / pastebin / telegram / ngrok | None |
| Every string constant in every class's constant pool | Reviewed — clean |
| Class coverage | 206/206 classes accounted for, nothing skipped |

## Every outbound connection in the jar

There are only six, and all are legitimate features:

| Destination | Used by | Purpose |
|---|---|---|
| `accounts.spotify.com`, `api.spotify.com` | SpotifyHUD / SpotifyAuth | Spotify OAuth + playback control |
| `api.mojang.com`, `sessionserver.mojang.com`, `api.minecraftservices.com` | SkinChanger | Public profile/skin lookup |
| `mc-heads.net/body/` | webhook embeds | Skin render thumbnail |
| `dpaste.com` | ConfigShare | Config upload/download — **user-initiated only** |
| *your* Discord webhook | CoordSnapper, SpawnerProtect | Alerts |
| Discord IPC (local pipe) | DiscordRPC | Rich Presence |

### About the webhooks

This is the part that *looks* like an IP grabber but isn't:

- The webhook URL is a **user setting that defaults to empty** (`new Setting<String>("Webhook", "")`).
- There is **no hardcoded fallback** — both URL normalisers are a plain `trim()`.
- The `serverIp` field in the payload is **the Minecraft server you are connected to**, not your
  IP address. It is read from `mc.getCurrentServerEntry()`.
- It posts to a webhook *you* configure. Nothing is sent anywhere else.

### About the PowerShell execution

`SpotifyHUD` runs `powershell -ExecutionPolicy Bypass -File <temp>.ps1`, which looks alarming.
The scripts are written by the client itself and are fully literal in the source — they call the
Windows `GlobalSystemMediaTransportControlsSessionManager` API to read the currently playing
track and send next/previous/play-pause. No network access, no downloads, no dynamic content.

## Dead code worth knowing about

- **`util/HwidUtil.java`** builds a machine fingerprint from MAC addresses — but `getHwid()` is
  **never called from anywhere**, and the system properties it reads decode to garbage. It is
  inert leftover from a licensing system.
- **`util/AuthBridge.java`** is completely stubbed: `isAuthOk()` is a hardcoded `return true`,
  no network.
- **`util/IntegrityCheck.java`** is a defunct anti-crack stub and is **never referenced** either.
- `com.water.auth.NativeAuth` / `NativeLoader` are referenced by name via `Class.forName` but
  were **never included in the jar**, so those checks always fail closed and do nothing.

All four are safe to delete if you want a smaller codebase.
