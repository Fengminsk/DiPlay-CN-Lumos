# DiPlay CN

> 简体中文说明见 [README.zh-CN.md](README.zh-CN.md)。本仓库基于上游 DiPlay `v0.2.11`。

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay.cn`; installs alongside official DiPlay.

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.

[Download the latest CN build](https://github.com/serein-morii/DiPlay-CN/releases/latest) · [Gitee](https://gitee.com/oneeyear/DiPlay-CN/releases/latest) · [CN optimization log](docs/CN_OPTIMIZATIONS.md) · [All CN releases](https://github.com/serein-morii/DiPlay-CN/releases) · [Upstream DiPlay](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.11)

![DiPlay home](site/assets/home.png)

## What CN adds on top of upstream

Every CN change is written down in the **[CN optimization log](docs/CN_OPTIMIZATIONS.md)** — each release, what changed and why. Current base: upstream `v0.2.11`.

- Custom dashboard turn card, CN edition: size 30–95 % and opacity 20–100 % sliders, day/night glass following the head unit, a trip info strip (arrival · duration · distance), and the card survives wireless session drops.
- In-app updates since `0.2.10-cn.8`: About → Check for updates downloads and installs the next CN build over the current one, settings kept. Four channels: Gitee (default), GitHub, gh-proxy.com, ghproxy.net.
- Boot auto-start ADB repair for firmwares that block third-party boot receivers.
- Optional delayed Bluetooth pause while CarPlay runs (5/10/15/30 s), so calls ring on CarPlay only; pairing is never touched.
- Smaller dashboard-map size option (125 % stream): smaller features, more map.
- Simplified Chinese by default when the car's language is unsupported; the wireless handoff watchdog steps aside once AirPlay is already active.
- Release APK built as a release variant with the official identity, sized like the official package, signed with one stable CN key since `0.2.10-cn.4` so updates overlay-install.

## 0.2.11 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. Wireless supports Wi-Fi Direct or the car’s existing hotspot; Wi-Fi Direct requires Android 10+; the APK supports Android 9+ for wired use.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

## What’s new in 0.2.11

- **Preferred Wi-Fi Direct channel**: Auto remains the default; save a supported 2.4/5 GHz channel for the next connection. Rejected or mismatched manual channels report an error. Channel choice is not a confirmed stutter fix.
- A custom dashboard turn card with size choices and position changes in 2% steps. Unknown maneuvers show no guessed arrow; expired guidance clears.
- Two-, three- or four-finger settings swipes, keeping three as the default, plus Android TV/remote controls that preserve ordinary touch and knob behavior.
- Opt-in read-only legacy vehicle-data detection under Location → Advanced vehicle data. Default DiLink 5.0 mode remains the default; only accepted fields/readings become runtime data. Stale-probe and battery-publication concurrency corrections are included.
- Optional automatic startup of the existing car hotspot, off by default, with verified permissions limited to DiPlay's own package.
- Wireless location/vehicle data on the runtime Wi-Fi link and parked-video availability delivered after SETUP/event-channel readiness. Non-P or unreadable gear still closes video.
- Retain artists across partial song updates and publish media-session metadata/artwork only when changed; position/play state keep updating.
- Android 9 audio API compatibility, failed-codec cleanup, settled-size/readiness checks after reconnect, an exact-error Android 10 P2P compatibility path in Auto mode, and a wired VPN restricted to DiPlay.
- Bounded wireless/media/theme and own-app exit diagnostics, without audio/video/packet payload recording or automatic uploads.

Optional legacy vehicle data, battery, wheel speed and parked video require authorized network ADB and supported readings. Dashboard, hotspot and audio effects depend on firmware and Android support. See [0.2.11 release notes](docs/RELEASE-NOTES-0.2.11.md) and [validation](docs/VALIDATION.md) for review corrections and device-test limits.

## Documentation

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [CN optimization log](docs/CN_OPTIMIZATIONS.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The website is available in English, Arabic, Russian, Ukrainian, Spanish and Simplified Chinese. The app interface supports those same six languages. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
