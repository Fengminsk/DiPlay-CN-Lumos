package com.miauto.app;

/**
 * 每个音源该用什么「焦点身份」和「播放身份」，以及为什么这两者必须一致。
 *
 * <p>纯逻辑，不碰 Android/SDK 类型，可直接 JVM 单测（见 {@code AudioFocusAttributionPolicyTest}）。
 * {@code IccoaManager.getAudioAttributesByType()} 只做一件事：把这里的 {@link Spec} 翻译成
 * {@code UCarCommon.AudioAttributes}。
 *
 * <h3>要修的断音</h3>
 *
 * <p>{@code AudioPlayerMgr} 用 {@link #focusKey} 那套字符串记「谁当前持有音频焦点」：
 *
 * <pre>
 * getKey(a) = a.getFocusGain() + "_" + a.getAudioFocusAudioAttributes().getUsage()
 *                               + "_" + a.getAudioFocusAudioAttributes().getContentType()
 * </pre>
 *
 * <p>而我们原先 {@code getAudioAttributesByType()} 返回 {@code null}，SDK 于是给<b>所有</b>音源套同一套
 * 默认属性 —— 音乐和通知落进<b>同一个键</b>、共享同一份焦点状态。谁先申请，这份状态就记在谁名下
 * （{@code AudioFocusChangeListener.mAudioTypes} 只装它那一个音源）；它一 {@code stopPlayer()}，
 * {@code mAudioTypes.size()==1} 成立 → 整份状态被 abandon 并从表里删除。另一条流接下来的每一帧
 * 查不到状态、现场新建一个「没焦点」的，而那个一次性闩已经用掉 → 每帧 {@code no focus, drop data}，
 * 只能等看门狗补。听感就是「连上了、手机在放歌、车机一下一下没声」。
 *
 * <h3>为什么非音乐音源不能配 {@code focusGain=1}</h3>
 *
 * <p>{@code requestAudioFocus()} 把这个 int <b>直接</b>当 {@code AudioFocusRequest.Builder} 的焦点类型用，
 * 1 就是 {@code AUDIOFOCUS_GAIN}：音乐会收到永久 {@code LOSS}，而 {@code handleLossAudioFocus} 在永久丢失时
 * 还会代发给手机一对 MEDIA_PAUSE —— 音乐被按停且不会自己回来。这比共享键更糟，v0.1.8.2 就是这么
 * 把事情办坏的。反过来，<b>不是 1 也照样出声</b>：{@code write()} 里 {@code focusGain != 1} 的分支会在没
 * 焦点时直接申请、且不受闩约束。所以「不配 1 就没声音」是误解。
 *
 * <p>在 2 和 3 之间一律取 <b>3（{@code MAY_DUCK}）</b>：SDK 对 {@code -3} 的分支<b>只打日志</b>，既不清
 * {@code mHasFocus} 也不 pause 播放器；而对 {@code -2} 会 {@code player.pause()}。音乐轨跑在 deep-buffer
 * 输出上，暂停再恢复会带一个爆破音外加一坨积压音频涌出 —— 实测平板上就有。
 *
 * <h3>播放身份必须和焦点身份一致（车机通道映射）</h3>
 *
 * <p>车机把「这条流送到哪一路功放通道」的映射表整个放在应用层<b>之下</b>（audio policy / HAL / ACDB：
 * usage → device → TDM slot → 具体通道），应用侧唯一的发言权就是 {@code AudioAttributes.usage}。
 * {@code AudioPlayerV2} 建 AudioTrack 时用的是 {@code getAudioAttributes()}（<b>播放</b>属性），
 * 而 getKey/申请焦点用的是 {@code getAudioFocusAudioAttributes()}（<b>焦点</b>属性）。
 *
 * <p>所以只把焦点侧声明成导航、播放侧仍留 {@code USAGE_MEDIA}，车机看到的就是一条「媒体流」，
 * 会按媒体的映射去铺通道，压根不会走它给导航预留的那一路。v0.1.9 第一版就是这么写的（当时是为了
 * 规避平板上「提示音掉进通知音量带被调成 0」），在车机上方向是反的。现在导航/语音助手的<b>播放</b>
 * 属性与焦点属性对齐，让车侧用同一身份同时决定通道映射和压低曲线。
 *
 * <p>平板与车机对 usage 的处理不同，且「车机把哪个 usage 认成导航通道」无法从应用侧查询
 * （{@code AudioDeviceInfo.getMaxChannels()} 是 API 30，车机是 API 29；通道映射本身也读不到），
 * 所以播放侧 usage 支持运行时覆盖（{@link #acceptedGuidanceUsage}），读取点在
 * {@code IccoaManager.getAudioAttributesByType()}：上车用 adb 一行扫候选，不用重编包。
 *
 * <p>通话音源（IP/MODEM CALL）仍然返回 {@code null}：改它会牵扯 {@code isVoiceCommunication()} 的
 * 通话态与 MediaSession sessionId，需要实车通话单独验证。<b>已知残留</b>：它今天仍与音乐共用焦点键。
 */
public final class AudioFocusAttributionPolicy {

    // SDK 的 UCarCommon.AudioType 取值（反编译核对），让本类不依赖 Android/SDK 类型。
    static final int TYPE_UNDEFINED = 0;
    static final int TYPE_IP_CALL = 1;
    static final int TYPE_MODEM_CALL = 2;
    static final int TYPE_AI_ASSISTANT = 3;
    static final int TYPE_RING = 4;
    static final int TYPE_NOTIFICATION = 5;
    static final int TYPE_TTS = 6;
    static final int TYPE_SYSTEM = 7;
    static final int TYPE_CAST_MUSIC = 16;

    // android.media.AudioManager 的焦点请求类型。写成字面量并在此注明，是因为本类要能在 JVM 单测里
    // 跑（不能 import android.media.*）；这些值自 API 8 起没变过。
    /** AUDIOFOCUS_GAIN —— 长期独占，会把别人打成永久 LOSS。只给音乐。 */
    static final int GAIN = 1;
    /** AUDIOFOCUS_GAIN_TRANSIENT —— 借用一会儿；SDK 收到 -2 会 pause 播放器，故不采用。 */
    static final int GAIN_TRANSIENT = 2;
    /** AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK —— 借用且允许压低；SDK 收到 -3 什么都不做。 */
    static final int GAIN_TRANSIENT_MAY_DUCK = 3;

    // android.media.AudioAttributes 的 usage / contentType，逐个用 `javap -constants` 对过
    // android-34 的 android.jar。⚠ 别凭记忆改：抄错不会编译失败，只会让这条流被车机认成别的用途
    // （USAGE_NOTIFICATION 是 5，11 其实是 ACCESSIBILITY）。
    private static final int USAGE_MEDIA = 1;
    /** ⚠ 播放属性取它 + CONTENT_TYPE_SPEECH 会命中 SDK 的 {@code isVoiceCommunication()}。 */
    static final int USAGE_VOICE_COMMUNICATION = 2;
    static final int USAGE_VOICE_COMMUNICATION_SIGNALLING = 3;
    private static final int USAGE_NOTIFICATION = 5;
    private static final int USAGE_NOTIFICATION_RINGTONE = 6;
    private static final int USAGE_ASSISTANCE_SONIFICATION = 13;
    private static final int USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12;
    private static final int USAGE_ASSISTANT = 16;
    /**
     * 车机（含 AAOS 类实现）里承载一路音频总线的设备类型；导航声常被单独挂到某个 BUS 上。
     * 音频拓扑快照里如果看到它，就说明这台车确实有「独立的一路」可映射。
     */
    public static final int DEVICE_TYPE_BUS = 21;
    private static final int CONTENT_TYPE_MUSIC = 2;
    private static final int CONTENT_TYPE_SONIFICATION = 4;
    private static final int CONTENT_TYPE_SPEECH = 1;

    // 播放属性保持 SDK 原样的音源（音乐、通知、铃声、系统音）用这一套 —— 音乐必须逐字段等于 SDK
    // 默认，那条路径才是今天被验证过能出声的；通知类等保留媒体音量带，避免在音量被调成 0 的车型上
    // 直接听不见提示音。
    private static final int PLAYBACK_USAGE_MEDIA = USAGE_MEDIA;
    private static final int PLAYBACK_CONTENT_TYPE_MUSIC = CONTENT_TYPE_MUSIC;
    private static final int PLAYBACK_STREAM_TYPE = 3; // AudioManager.STREAM_MUSIC

    /** 导航播报默认识别用的 usage；也是覆盖项没设置时的取值。 */
    public static final int DEFAULT_GUIDANCE_USAGE = USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;

    /**
     * 一个音源的身份：焦点三件套进 {@code getKey()} 和 {@code AudioFocusRequest}，播放三件套进
     * {@code AudioTrack}（决定音量带、路由与车机通道映射）。
     */
    public static final class Spec {
        public final int focusGain;
        public final int focusUsage;
        public final int focusContentType;
        public final int playbackUsage;
        public final int playbackContentType;
        public final int streamType;

        Spec(int focusGain, int focusUsage, int focusContentType,
             int playbackUsage, int playbackContentType) {
            this.focusGain = focusGain;
            this.focusUsage = focusUsage;
            this.focusContentType = focusContentType;
            this.playbackUsage = playbackUsage;
            this.playbackContentType = playbackContentType;
            this.streamType = PLAYBACK_STREAM_TYPE;
        }
    }

    /**
     * @return 该音源应有的身份；{@code null} = 交回 SDK 用它的默认（行为与本改动之前完全一致）。
     */
    public static Spec specFor(int audioTypeValue) {
        return specFor(audioTypeValue, 0);
    }

    /**
     * @param guidanceUsageOverride 导航播报播放侧 usage 的运行时覆盖（0 = 不覆盖）。只影响
     *        TTS/语音助手的<b>播放</b>属性；焦点属性一律按表里的 GUIDANCE/ASSISTANT 走，因为压低
     *        音乐这件事是焦点侧触发的，扫通道映射时不该连带改掉它。
     *        非法值（会命中通话态的、或未知用途的）被 {@link #acceptedGuidanceUsage} 挡掉。
     */
    public static Spec specFor(int audioTypeValue, int guidanceUsageOverride) {
        switch (audioTypeValue) {
            case TYPE_CAST_MUSIC:
                // 与 SDK 默认逐字段相同 ⇒ 焦点键仍是 "1_1_2"，音乐这一路行为不变。
                return new Spec(GAIN, USAGE_MEDIA, CONTENT_TYPE_MUSIC,
                        PLAYBACK_USAGE_MEDIA, PLAYBACK_CONTENT_TYPE_MUSIC);

            case TYPE_NOTIFICATION:
                return new Spec(GAIN_TRANSIENT_MAY_DUCK, USAGE_NOTIFICATION, CONTENT_TYPE_SONIFICATION,
                        PLAYBACK_USAGE_MEDIA, PLAYBACK_CONTENT_TYPE_MUSIC);

            case TYPE_RING:
                return new Spec(GAIN_TRANSIENT_MAY_DUCK, USAGE_NOTIFICATION_RINGTONE, CONTENT_TYPE_SONIFICATION,
                        PLAYBACK_USAGE_MEDIA, PLAYBACK_CONTENT_TYPE_MUSIC);

            case TYPE_SYSTEM:
                return new Spec(GAIN_TRANSIENT_MAY_DUCK, USAGE_ASSISTANCE_SONIFICATION, CONTENT_TYPE_SONIFICATION,
                        PLAYBACK_USAGE_MEDIA, PLAYBACK_CONTENT_TYPE_MUSIC);

            case TYPE_TTS: {
                int playback = acceptedGuidanceUsage(guidanceUsageOverride, DEFAULT_GUIDANCE_USAGE);
                return new Spec(GAIN_TRANSIENT_MAY_DUCK, USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, CONTENT_TYPE_SPEECH,
                        playback, CONTENT_TYPE_SPEECH);
            }

            case TYPE_AI_ASSISTANT: {
                int playback = acceptedGuidanceUsage(guidanceUsageOverride, USAGE_ASSISTANT);
                return new Spec(GAIN_TRANSIENT_MAY_DUCK, USAGE_ASSISTANT, CONTENT_TYPE_SPEECH,
                        playback, CONTENT_TYPE_SPEECH);
            }

            case TYPE_IP_CALL:
            case TYPE_MODEM_CALL:
            case TYPE_UNDEFINED:
            default:
                return null;
        }
    }

    /**
     * 这个 usage 能不能拿去做<b>播放</b>属性？
     *
     * <p>挡掉两类：
     * <ul>
     *   <li>{@code VOICE_COMMUNICATION}(2) / {@code VOICE_COMMUNICATION_SIGNALLING}(3)：
     *       {@code AudioPlayerMgr.isVoiceCommunication()} 判的就是播放属性 {@code usage==2 && ct==1}，
     *       命中就会去挂 MediaSession sessionId 并准备 {@code setMode(3)} —— 扫通道映射时误设成 2
     *       会把一条导航播报变成通话态，是这次调试里唯一能真正把车机音频搞乱的操作。</li>
     *   <li>{@code UNKNOWN}(0) 与负数：0 同时是「不覆盖」的哨兵值，必须在这里被排除，否则覆盖项
     *       未设置时的语义会和「显式设成 UNKNOWN」混在一起。</li>
     * </ul>
     *
     * @param requested 想用的值；0 或非法时返回 {@code fallback}
     * @param fallback  该音源在表里的默认值
     */
    public static int acceptedGuidanceUsage(int requested, int fallback) {
        if (requested == 0) return fallback;
        if (requested < 0) return fallback;
        if (requested == USAGE_VOICE_COMMUNICATION) return fallback;
        if (requested == USAGE_VOICE_COMMUNICATION_SIGNALLING) return fallback;
        if (requested == TYPE_UNDEFINED) return fallback;
        return requested;
    }

    /**
     * SDK {@code AudioPlayerMgr.getKey()} 的等价式。测试用它钉住「谁和谁共享同一份焦点状态」。
     *
     * <p>注意用的是<b>焦点</b>属性而不是播放属性 —— 这就是播放侧可以单独去适配车机通道映射、
     * 而不影响断音修复的原因。
     */
    public static String focusKey(Spec spec) {
        return spec.focusGain + "_" + spec.focusUsage + "_" + spec.focusContentType;
    }

    /** SDK 拿到 {@code null} 时给所有音源的同一套默认属性，等价于本键。测试用它做基线。 */
    public static String sdkDefaultFocusKey() {
        return GAIN + "_" + USAGE_MEDIA + "_" + CONTENT_TYPE_MUSIC;
    }

    private AudioFocusAttributionPolicy() { }
}
