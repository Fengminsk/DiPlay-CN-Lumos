package com.miauto.app;

/**
 * 车机物理按键（Android {@code KeyEvent} keyCode）→ ICCOA UIBC 键值
 * （{@code UCarCommon.KeyCodeType} 的 int 值）的纯映射。
 *
 * <p><b>为什么需要这一层</b>：方向盘上一曲/下一曲在 MiAuto 里从来没到过手机。
 * 按键从 {@code CarKeyReceiver} 进来，最终落到 {@code CommManager.sendKey()}，而那里第一行是
 * {@code if (_connectionState.value !is TransportStarted) return} —— 判断的是 Android Auto 的
 * 传输状态。MiAuto 运行期只跑 ICCOA（{@code BuildConfig.ICCOA_ONLY=true}），AA 传输永远不存在，
 * 于是每一颗按键都在这里被静默丢弃。要接上，就得把 Android 键码翻成 ICCOA 的反控键码。
 *
 * <p>两套键码大部分数值相同（NEXT 87 / PREVIOUS 88 / PLAY 126 / PAUSE 127 / BACK 4 /
 * DPAD 19~23 / ENTER 66），只有两个必须真正翻译：
 * <ul>
 *   <li>{@code KEYCODE_MEDIA_PLAY_PAUSE(85)} —— SDK 枚举里<b>没有</b>「播放/暂停」这一个键，
 *       只有分开的 PLAY(126) / PAUSE(127)。必须按手机当前播放状态二选一，否则按一下是暂停、
 *       再按一下还是暂停，用户会以为「按键没反应」；</li>
 *   <li>{@code KEYCODE_MEDIA_STOP(86)} —— 枚举里也没有 STOP，退化成 PAUSE。</li>
 * </ul>
 * 音量、快进快退、语音助手等不在 ICCOA 可控键表内（音量由车机自己调，不该透传给手机），
 * 返回 {@link #NONE} 交由调用方丢弃。
 *
 * <p><b>为什么不用 {@code KeyEvent.KEYCODE_*} 常量</b>：本类刻意不依赖 android.jar，
 * 这样能在 JVM 单测里直接把映射钉死（见 {@code MediaKeyMapTest}）。两套常量数值一致，
 * 已逐个核对过 {@code android.view.KeyEvent} 与 SDK 的 {@code KeyCodeType.getValue()}。
 */
final class MediaKeyMap {

    /** 不能/不需要下发到手机的键。 */
    static final int NONE = 0;

    // ---- Android KeyEvent 常量（同值字面量，见类注释）----
    private static final int A_BACK = 4;
    private static final int A_DPAD_UP = 19;
    private static final int A_DPAD_DOWN = 20;
    private static final int A_DPAD_LEFT = 21;
    private static final int A_DPAD_RIGHT = 22;
    private static final int A_DPAD_CENTER = 23;
    private static final int A_ENTER = 66;
    private static final int A_HEADSETHOOK = 79;
    private static final int A_MEDIA_PLAY_PAUSE = 85;
    private static final int A_MEDIA_STOP = 86;
    private static final int A_MEDIA_NEXT = 87;
    private static final int A_MEDIA_PREVIOUS = 88;
    private static final int A_MIC_MUTE = 91;
    private static final int A_MEDIA_PLAY = 126;
    private static final int A_MEDIA_PAUSE = 127;

    // ---- ICCOA KeyCodeType 值 ----
    private static final int S_BACK = 4;
    private static final int S_DPAD_UP = 19;
    private static final int S_DPAD_DOWN = 20;
    private static final int S_DPAD_LEFT = 21;
    private static final int S_DPAD_RIGHT = 22;
    private static final int S_DPAD_CENTER = 23;
    private static final int S_ENTER = 66;
    private static final int S_MEDIA_NEXT = 87;
    private static final int S_MEDIA_PREVIOUS = 88;
    private static final int S_MIC_MUTE = 91;
    private static final int S_MEDIA_PLAY = 126;
    private static final int S_MEDIA_PAUSE = 127;

    private MediaKeyMap() { }

    /**
     * 需要「按下时二选一」的键族：PLAY_PAUSE(85) / HEADSETHOOK(79) / STOP(86)。
     *
     * <p>这类键的目标键值取决于手机<b>按下那一刻</b>是否在放歌，因此按下与抬起必须用<b>同一个</b>
     * 目标键值（见 {@link #resolve}）。其余键的翻译与播放状态无关，按下抬起天然一致。
     */
    static boolean isPlayPauseFamily(int androidKeyCode) {
        return androidKeyCode == A_MEDIA_PLAY_PAUSE
                || androidKeyCode == A_HEADSETHOOK
                || androidKeyCode == A_MEDIA_STOP;
    }

    /**
     * 一次按键事件解析出来的结果：本次要下发什么、以及要给下一事件留下的缓存。
     *
     * <p>{@code nextHeldCode} 存在的唯一目的就是让「同一颗物理键的 UP」复用 DOWN 选定的键值。
     */
    static final class KeyStroke {
        /** 本次要下发的 ICCOA 键值；{@link #NONE} 表示丢弃。 */
        final int sdkCode;
        /** 调用方需要保存的「本键按下时选定的键值」；{@link #NONE} 表示无需保存。 */
        final int nextHeldCode;

        KeyStroke(int sdkCode, int nextHeldCode) {
            this.sdkCode = sdkCode;
            this.nextHeldCode = nextHeldCode;
        }
    }

    /**
     * 按「按下 / 抬起」成对解析键值 —— 修的是「按一下播放暂停，观感像没反应」。
     *
     * <p><b>为什么必须有这一层</b>：{@code musicPlaying} 在 DOWN 里就被翻转成「预期状态」
     * （否则连点会连发同一个动作）。如果 UP 再调用一次 {@link #toSdk} 用新状态重算，就会得到
     * <b>相反的键</b>：DOWN 发 PLAY、UP 发 PAUSE。手机端两个动作都消费时，一次按键变成
     * 「播放又暂停」，用户看到的仍是「按键没反应」。所以 UP 一律复用 DOWN 记下的键值。
     *
     * @param androidKeyCode 物理键码
     * @param down           true = ACTION_DOWN
     * @param musicPlaying   手机当前是否在放歌（仅 PLAY_PAUSE 族用得上）
     * @param heldCode       上一事件留下的缓存（第一次按键传 {@link #NONE}）
     */
    static KeyStroke resolve(int androidKeyCode, boolean down, boolean musicPlaying, int heldCode) {
        boolean family = isPlayPauseFamily(androidKeyCode);
        // 抬起：复用按下时选定的键值，并清掉缓存（一次按键只缓存一次）。
        if (!down && family && heldCode != NONE) {
            return new KeyStroke(heldCode, NONE);
        }
        int sdk = toSdk(androidKeyCode, musicPlaying);
        // 按下：把选定的键值留给对应的 UP。
        if (down && family) {
            return new KeyStroke(sdk, sdk);
        }
        // 其余情况（非本键族，或抬起时缓存已丢）：正常翻译，不留缓存。
        return new KeyStroke(sdk, NONE);
    }

    /**
     * @param androidKeyCode {@code KeyEvent.getKeyCode()}
     * @param musicPlaying   手机当前是否在放歌（来自 ICCOA 的 MusicInfo）。仅 PLAY_PAUSE 用得上：
     *                       正在放 → 发 PAUSE；已停 → 发 PLAY。
     * @return ICCOA 键值；{@link #NONE} 表示这颗键不该下发给手机
     * @see #resolve 成对的按下/抬起请走这个方法，单发一次直接用本方法
     */
    static int toSdk(int androidKeyCode, boolean musicPlaying) {
        switch (androidKeyCode) {
            case A_MEDIA_NEXT:
                return S_MEDIA_NEXT;
            case A_MEDIA_PREVIOUS:
                return S_MEDIA_PREVIOUS;
            case A_MEDIA_PLAY:
                return S_MEDIA_PLAY;
            case A_MEDIA_PAUSE:
                return S_MEDIA_PAUSE;
            case A_MEDIA_PLAY_PAUSE:
            case A_HEADSETHOOK:          // 耳机线控的单键，语义上就是「播放/暂停」
                return musicPlaying ? S_MEDIA_PAUSE : S_MEDIA_PLAY;
            case A_MEDIA_STOP:
                return S_MEDIA_PAUSE;    // 枚举无 STOP，停 ≈ 暂停
            case A_BACK:
                return S_BACK;
            case A_DPAD_UP:
                return S_DPAD_UP;
            case A_DPAD_DOWN:
                return S_DPAD_DOWN;
            case A_DPAD_LEFT:
                return S_DPAD_LEFT;
            case A_DPAD_RIGHT:
                return S_DPAD_RIGHT;
            case A_DPAD_CENTER:
                return S_DPAD_CENTER;
            case A_ENTER:
                return S_ENTER;
            case A_MIC_MUTE:
                return S_MIC_MUTE;
            default:
                return NONE;
        }
    }
}
