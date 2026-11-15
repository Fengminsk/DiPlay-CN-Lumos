# ICCOA wired protocol: current findings and port plan

Date: 2026-11-15
Status: investigation + port plan. No newer car-side SDK has been obtained yet.

## 1. Versions and samples

| Item | Version | Size | SHA-256 / notes |
|---|---|---|---|
| DiPlay bundled sink SDK (`icoca/libs/icar-sdk.dex`) | v1.5.3-202401092110-支持方控, protocol 1.5 | 7,084,144 | `E5236EB67F715A83EB3BF8F935547FEBE6459A1A39A7B05858DF2243BB93BA91` |
| DiPlay bundled `libovmsink.so` | build path `1.5.3verson` | 1,481,168 | `298B9BFF630751FBB6A934E2E6F549BCEA83D619ED6D6B2EADD63BA68B231F34` |
| MiAuto repo app | 0.2.0 (2026-11-06) | — | same SDK dex/native libs as DiPlay |
| Car-side ICCOA demo APK (87g) | v1.5.1-202307102121 | 5,170,469 | `EB59CF63C2A6348435E21C1CAB20411182FA28B3E8970A4DBFE15D1CADC3FED5` (older than DiPlay) |
| CarWith phone app (APKPure) | 3.8.1 | 62,300,966 | 8 dex; source transport is in `libmicastcore.so` |
| Car-side target (Quark share, login-gated) | `ICCOA Carlink [1.5.3-2025-03-04].apk`, package `com.ucarhu.demo`, versionCode 8 | 7,986,924 | metadata only; download returns `401 require login [account null]` |

The phone reports `clientHello protocolVersion=2305`, while the bundled SDK only knows
protocol 1.5. MiAuto's own `FEASIBILITY.md` lists this SDK version drift as risk R1.

## 2. What the car-side logs prove

- Every wired session ends with `WifiDisplaySink` receiving
  `SET_PARAMETER ... wfd_trigger_method: TEARDOWN` from the phone, followed by
  `onNativeWfdSinkStopped` and `UCar_ShareLink: Disconnect`.
- Before that, the car's AOA data path is alive: the last video/audio buffer is within
  4–37 ms of the stop, RTSP `GET_PARAMETER` is answered in ~1 ms, and both heartbeat
  directions are current. `MediaReceiver -104` and
  `invalid parameter for bulkRead` happen after the sink stop and are cleanup effects.
- `p2p0 rx/tx = 0` is a wrong metric for an AOA session; do not use it as evidence that
  the data plane stopped.
- Session lifetimes observed: 9, 13, 17, 25, 33, 47 and 105 s. There is no fixed 30 s
  car-side watchdog.
- In 1456 the car log shows `投屏 Surface 被系统销毁 → pauseCast` 2.7 s before the stop.
  In 1527 the surface is still valid until the stop. Surface loss explains some cases,
  not all.
- The car calls `stopMiracastAndUCarChannel` and `CastManager: stop Cast` only after
  `onWfdSinkStopped`; at that point it is cleaning up, not initiating the stop.

## 3. What the phone-side native code proves

`CarWith 3.8.1 / lib/arm64-v8a/libmicastcore.so` is AOSP `WifiDisplaySource` plus Mi
extensions. Relevant strings:

```
Received TEARDOWN request.
Sending TEARDOWN trigger.
TEARDOWN trigger timed out, forcing disconnection.
Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER
iccoa_support_fusion
iccoa_support_hevc
Sink report iccoa_support_fusion = %s
```

CarWith Java state machine (`com.miui.carlink.castfwk.CarlinkStateMachine`) contains:

```
WIFI_DISABLE_MI_CAR_P2P to disconnect!
PREPARE_TO_DISCONNECT
CMD_DISCONNECT_CAST
completeDisconnect
notifyDisconnectByUser
```

`com.miui.carlink.ScreenStateReceiver` only changes BLE wakeup advertising on screen
on/off; it does not directly stop casting.

`CastController` has a 20-minute `ScreenOnTask` delay (not a 30-second watchdog).

## 4. Compatibility experiments already in the build

- 115: stop CarPlay host/service/background session on an ICCOA protocol switch.
- 116: circuit-break repeated abnormal reconnects (3 in 120 s).
- 117: open `IcocaProjectionActivity` when the engine connects while the home screen is
  up, so the phone always has a Surface.
- 118: WFD `SET_PARAMETER wfd_idr_request` keepalive every 8 s (experimental).
- 119: restore MiAuto's `com.ucar.vehiclesdk.util.BitmapUtil` shadow class.
- 120: `UCarConfig.setSupportAvRawPacket(false)` so the sink stops advertising
  `wfd_av_raw_packet: true` and falls back to standard MPEG-TS.

The 120 change has not been exercised end-to-end because the test phone did not
complete the ICCOA pairing prompt.

## 5. Port plan for the 2025 car-side APK

When `ICCOA Carlink [1.5.3-2025-03-04].apk` is available:

1. Verify package/version:
   `aapt2 dump badging` should show `com.ucarhu.demo`, version `1.5.3-2025-03-04`,
   versionCode 8.
2. Unzip and record hashes of `lib/arm64-v8a/libovmsink.so`, `lib/arm64-v8a/libusbio.so`,
   and `assets/ccd.data`. Compare `libovmsink.so` with the current
   `298B9BFF…1F34`. If it differs, it contains the newer sink.
3. Locate its runtime dex (the demo APK has `classes.dex`/`classes2.dex`; a full vendor
   APK may have an injected `icar-sdk.dex` or a similar `classesN.dex`). Extract the
   class set under `com/ucar`, `com/share/connect`, `com/ucarsink`.
4. Prefer replacing the native sink first:
   - copy the 2025 `libovmsink.so`/`libusbio.so` into
     `icoca/src/main/jniLibs/arm64-v8a/` (and armeabi-v7a if present);
   - keep the existing `icar-sdk.dex` and Java layer;
   - rebuild 121 and test wired ICCOA.
   This isolates the native WFD/sink/protocol changes from the Java state machine.
5. If the native swap is not ABI/API compatible (missing JNI symbols), replace the SDK
   dex as a second step:
   - drop the 2025 dex into `icoca/libs/`;
   - update `injectIccoaDex` if the file name changes;
   - re-run `:mobile:assembleStandaloneRelease`;
   - verify `SinkNative`/`UCarAdapter` method signatures against the current Java
     reflection/config calls.
6. Keep the current `UCarConfig` overrides (`setSupportHevc(false)`,
   `setSupportAvRawPacket(false)`) for the first test; re-enable one capability at a
   time only after the baseline is stable.
7. Test matrix: cold start + tap ICCOA; 60 s idle; 5 min idle; touch during session;
   screen off/on; CarPlay→ICCOA switch; ICCOA→CarPlay switch. Capture
   `iccoa-link.log` and `iccoa-aoa-probe.log` from COS for each run.

## 6. If the 2025 APK is still unavailable

The missing evidence is the phone source logcat. Two ways to get it:

1. Phone USB debugging: `adb logcat` filtered on
   `WifiDisplaySource|CarlinkStateMachine|CastController|TEARDOWN|WIFI_DISABLE`.
2. CarWith 3.8.1 is already installed on the pad with an exported
   `com.miui.carlink/.castfwk.usb.AOAActivity`; plug the pad into the car's USB
   accessory port and capture pad logcat over Wi-Fi ADB.

Until one of those is available, the remaining hypotheses are:

- the phone's system disables Wi-Fi P2P (`WIFI_DISABLE_MI_CAR_P2P`) while the wired AOA
  session is still up;
- a 2026 source / 2024 sink capability or state-machine mismatch (HEVC, raw packet,
  UIBC, or session setup);
- a car-side foreground/Surface loss (confirmed in 1456, absent in 1527).
