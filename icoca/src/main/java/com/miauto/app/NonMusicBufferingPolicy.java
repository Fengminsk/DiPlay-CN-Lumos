package com.miauto.app;

/**
 * 非音乐音源的预缓冲水位：导航播报与通知音从 SDK 的下限（100ms）抬到 250ms。
 *
 * <p>纯逻辑（只算数，不碰 Android/SDK 类型），可直接 JVM 单测（见
 * {@code NonMusicBufferingPolicyTest}）；反射调用点在 {@code IccoaManager}。
 *
 * <h3>要修的杂音</h3>
 *
 * <p>{@code AudioPlayerV2.PlayThread} 构造时按音源分两档：音乐用 {@code MAX_BUFFERING_LEN_MS}(600)
 * 并等手机远端下发实际值；<b>其它所有音源一律写死 {@code updateAudioPlayParams(false, 100, 0, 10)}</b>
 * —— 起步就在允许范围的最下限 100ms。手机对导航播报和通知音<b>从不</b>下发更大的值，于是它们一直用
 * 100ms 预缓冲跑。车上实测一份两分半的窗口：导航 11 句建、11 句拆、<b>52 次</b>「等缓冲填满」
 * （≈每句 4.7 次）、<b>12 次</b>句首数据被丢；同一窗口里音乐因为手机把水位下发到 300ms，PCM 队列
 * 全程钉在满水位、一次都没回填。听感就是导航人声发碎，且与音量大小、与是否同时放音乐无关。
 *
 * <h3>为什么是 250ms</h3>
 *
 * <p>SDK 给非音乐流的硬上限是 {@code MID_BUFFERING_LEN_MS}=300ms（{@code updateAudioPlayParams} 里
 * {@code buffingCount <= mMaxBufferingLen} 才收），低于 {@code MIN_BUFFERING_LEN_MS}=100ms 同样被丢
 * 弃，所以可动区间只有 100~300。
 *
 * <p>先取 200 上车实测：每句导航的「等缓冲填满」从 <b>4.73 次/句</b> 降到 <b>1.00 次/句</b> ——
 * 剩下这一次是播放器冷启动必然的初次预填，属于结构性下限，再往上抬也消不掉。也就是说句中回填这条
 * 在 200ms 就已经基本解决了，继续加水位换来的只有出声延迟。
 *
 * <p>取到 250 是<b>实听之后人定的值</b>，不是推导出来的：区间里再往上一点抗抖动余量更大，代价是每句
 * 导航比 SDK 原样晚约 150ms 出声（比 200ms 那档再多 50ms）。<b>如果之后听到播报明显变"迟"，退回
 * 200 即可</b>，这个数只改 {@link #TARGET_BUFFERING_MS} 一处。
 *
 * <h3>为什么这个值能被应用侧改</h3>
 *
 * <p>{@code AudioPlayerMgr.createPlayer()} 在建完播放器后会立刻读
 * {@code mPlayersParams.get(type)} 并应用；而 {@code updateAudioPlayerControlData(controlData)}
 * <b>无论播放器在不在</b>都会先把参数存进 {@code mPlayersParams}。所以我们在会话建立时推一次，
 * 值会被记住、在第一句播报建播放器的那一刻生效，不存在"晚一句"的问题；再在每次真正申请焦点时补一次，
 * 防止手机中途下发更小的值把它盖回去。
 *
 * <p>注意 {@code AudioPlayerV2.updateAudioPlayParams} 是以 {@code updateByRemote=true} 转发的，
 * 所以我们推的值在 SDK 看来与手机下发的同等权威。
 */
public final class NonMusicBufferingPolicy {

    /** 抬到这一档；SDK 对非音乐流只接受 100~300，越界静默丢弃。 */
    public static final int TARGET_BUFFERING_MS = 250;

    // UCarAudioManager.AudioPlayerControl 里的常量（反编译核对），本类为了能在 JVM 单测里跑而写成字面量。
    static final int SDK_MIN_BUFFERING_MS = 100;
    static final int SDK_NON_MUSIC_MAX_BUFFERING_MS = 300;
    static final int SDK_MUSIC_MAX_BUFFERING_MS = 600;
    static final int SDK_MIN_ADJUST_STEP_MS = 10;

    // UCarCommon.AudioType 取值（反编译核对）。
    static final int TYPE_AI_ASSISTANT = 3;
    static final int TYPE_RING = 4;
    static final int TYPE_NOTIFICATION = 5;
    static final int TYPE_TTS = 6;
    static final int TYPE_SYSTEM = 7;
    static final int TYPE_CAST_MUSIC = 16;

    /**
     * 这个音源要不要抬水位，抬到多少。
     *
     * <p>只动<b>导航播报</b>与<b>通知音</b>：这两个是实测确认被 100ms 下限卡出碎音的。
     * <b>已知未覆盖</b>：铃声(4)/系统音(7)/语音助手(3) 走的是同一条"非音乐=100ms 起步"的代码路径，
     * 理论上同样会碎，只是本次没有它们的实车样本，不猜着一起改；要扩只需在这里加对应分支并补一条断言。
     * 音乐(16) 绝不参与：它的水位由手机按 600ms 档远端调节，是今天唯一被验证过不卡的流。
     *
     * @return 要应用的毫秒数；{@code 0} = 不动这个音源
     */
    public static int bufferingMsFor(int audioTypeValue) {
        switch (audioTypeValue) {
            case TYPE_TTS:
            case TYPE_NOTIFICATION:
                return clampForNonMusic(TARGET_BUFFERING_MS);
            case TYPE_CAST_MUSIC:
            case TYPE_AI_ASSISTANT:
            case TYPE_RING:
            case TYPE_SYSTEM:
            default:
                return 0;
        }
    }

    /**
     * 变速补偿步进。保持 SDK 给非音乐流的默认值 10ms：本次要治的是"回填等待"，不是"追速"；
     * 顺手改它会同时动到语速，属于另一个变量。
     */
    public static int speedAdjustStepMs() {
        return SDK_MIN_ADJUST_STEP_MS;
    }

    /**
     * 需要在会话建立时预先推一次的音源。
     *
     * <p>之所以能在播放器还不存在时就推：{@code updateAudioPlayerControlData(controlData)} 无条件把参数
     * 存进 {@code mPlayersParams}，而 {@code createPlayer()} 建好播放器后会立刻读它并应用，且
     * {@code stopPlayer()} 不清 {@code mPlayersParams} —— 一次推送对整场会话的每一句都有效。
     *
     * <p>返回的是 {@code UCarCommon.AudioType} 的取值，由调用方翻成枚举对象。只列
     * {@link #bufferingMsFor} 会应答非零的那些，两处判定共用一张表，避免改一处漏一处。
     */
    public static int[] typesToPrime() {
        int[] out = new int[2];
        int n = 0;
        for (int t : new int[]{TYPE_AI_ASSISTANT, TYPE_RING, TYPE_NOTIFICATION, TYPE_TTS, TYPE_SYSTEM}) {
            if (bufferingMsFor(t) > 0 && n < out.length) out[n++] = t;
        }
        if (n == out.length) return out;
        int[] tight = new int[n];
        System.arraycopy(out, 0, tight, 0, n);
        return tight;
    }

    /**
     * 把目标值夹进 SDK 真正接受的区间。
     *
     * <p>必须显式做：{@code updateAudioPlayParams} 对越界值是<b>静默丢弃</b>（外层 if 不成立就什么都不做），
     * 写错一个数字不会崩、不会报错，只会让水位停在 100ms 不动 —— 那种"改了但没生效"最难查。
     * 夹完仍不合法（比如常量被改乱导致区间为空）就返回 0 表示干脆别推。
     */
    static int clampForNonMusic(int requestedMs) {
        if (requestedMs < SDK_MIN_BUFFERING_MS) return SDK_MIN_BUFFERING_MS;
        if (requestedMs > SDK_NON_MUSIC_MAX_BUFFERING_MS) return SDK_NON_MUSIC_MAX_BUFFERING_MS;
        return requestedMs;
    }

    /**
     * 这个音源会不会被抬水位。
     *
     * <p>单独露出来是给日志台账用的：只有会被抬的音源才需要报一行，音乐/通话即使出现在回调里也不该报。
     */
    public static boolean isBufferingTarget(int audioTypeValue) {
        return bufferingMsFor(audioTypeValue) > 0;
    }

    /**
     * 水位日志的台账：按音源各记一次。
     *
     * <p>这里必须是<b>每个音源一条</b>，不能是一个"这个会话报过了"的总开关。第一版就是用的一个
     * boolean，结果设备上只看到 {@code …（STREAM_NOTIFICATION）} 一行，导航明明也被抬了却从日志
     * 里看不出来 —— 在车上排查时这会直接导致误判"导航没生效"。台账按音源走，才既能证明每个目标
     * 音源都报过、又不会每句播报都刷一条。
     *
     * <p>纯函数、由调用方（{@code IccoaManager}）持有那份 int[]，因此可以在 JVM 里单测。
     *
     * @param announced 已报过的音源取值集合
     * @param typeValue 本次要判断的音源取值
     * @return 本次是否应当打日志（已报过同类则 false）
     */
    public static boolean shouldAnnounce(int[] announced, int typeValue) {
        if (!isBufferingTarget(typeValue)) return false;
        return indexOf(announced, typeValue) < 0;
    }

    /** 把某个音源记进台账，返回新的集合（入参不变）。 */
    public static int[] noteAnnounced(int[] announced, int typeValue) {
        if (indexOf(announced, typeValue) >= 0) return announced;
        int[] out = new int[announced.length + 1];
        System.arraycopy(announced, 0, out, 0, announced.length);
        out[announced.length] = typeValue;
        return out;
    }

    /**
     * 某个音源的水位说明。带上下限是因为 SDK 对越界值是<b>静默丢弃</b>的，日志里必须能看出我们
     * 究竟请求了多少、允许区间是多少，否则"改了没生效"和"改了生效了"在报告里长得一样。
     */
    public static String announceLine(int audioTypeValue, String audioTypeName) {
        return "音频水位：" + audioTypeName + " 预缓冲 " + SDK_MIN_BUFFERING_MS + "ms → "
                + bufferingMsFor(audioTypeValue) + "ms（SDK 对非音乐流只接受 "
                + SDK_MIN_BUFFERING_MS + "~" + SDK_NON_MUSIC_MAX_BUFFERING_MS
                + "ms，超出会被静默丢弃）";
    }

    private static int indexOf(int[] values, int wanted) {
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == wanted) return i;
            }
        }
        return -1;
    }

    private NonMusicBufferingPolicy() { }
}
