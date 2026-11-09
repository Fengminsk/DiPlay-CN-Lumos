package com.miauto.app;

/**
 * 「音频焦点已经丢了、但 SDK 自己不会再要回来」的判定。
 *
 * <p>纯逻辑，不碰 Android API，可直接 JVM 单测（见 {@code AudioFocusPolicyTest}）。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>反编译 {@code com.ucar.vehiclesdk.player.AudioPlayerMgr#write(AudioType, ByteBuffer)} 后可以
 * 看到一个「一次性闩」：SDK 只在
 * <ul>
 *   <li>{@code createPlayer()} 成功，或</li>
 *   <li>外部调用 {@code allowGainAudioFocus()}</li>
 * </ul>
 * 时才把 {@code mEnabledAudioFocusGain} 置成 {@code true}；而 {@code write()} 里只有在它<b>为真</b>
 * 的时候才会去 {@code requestAudioFocus()}，随后立刻把它置成 {@code false}：
 *
 * <pre>
 * if (attributes.getFocusGain() == 1) {
 *     if (this.mEnabledAudioFocusGain) {
 *         if (!bean.mHasFocus) { requestAudioFocus(...); this.mEnabledAudioFocusGain = false; }
 *         else                 { this.mEnabledAudioFocusGain = false; }
 *     }
 * }
 * ...
 * if (bean.mHasFocus) player.write(data); else Log.i("no focus, drop data, type = " + type);
 * </pre>
 *
 * <p>于是一旦出现下面这些终态，SDK 就<b>再也不会</b>重新申请焦点，手机推来的音频帧被
 * 一行行 {@code no focus, drop data} 丢掉 —— 车机上表现为「连接正常、手机上音乐在放、车机没声音」：
 * <ol>
 *   <li>{@code AUDIOFOCUS_LOSS}（{@code -1}）：永久丢失。系统只在「暂时丢失」后才会自动把焦点还回来，
 *       永久丢失之后必须应用自己重新 {@code requestAudioFocus()}，SDK 却把闩已经用掉了；</li>
 *   <li>{@code AUDIOFOCUS_REQUEST_FAILED}（{@code -4}）：申请当场被拒（车机在切换音源、通话中、
 *       或音频策略暂时不允许抢占）。同样没有任何重试；</li>
 *   <li><b>本音源一次事件都没收到</b>（{@link #NO_EVENT}）：过去它最常见，成因是音乐与通知共用同一套
 *       默认属性、因而共用同一个 {@code AudioFocusState}；通知放完把状态移出表，音乐的下一帧就落到
 *       「新建状态 = 没焦点，而闩已用掉」的死角。该共享已由 {@link AudioFocusAttributionPolicy}
 *       拆开，<b>这一档现在主要剩通话音源</b>（它今天仍与音乐同键，见那里的「已知残留」）。</li>
 * </ol>
 *
 * <p>判定放在这里而不是散在回调里，是为了能单测「什么时候该补、补几次就停」，
 * 免得变成无限抢焦点、把车机自己的收音机/导航反复顶掉。
 */
public final class AudioFocusPolicy {

    /** 还没收到过任何焦点回调。 */
    public static final int NO_EVENT = Integer.MIN_VALUE;

    /** SDK 的 {@code AUDIOFOCUS_ABANDONED}：焦点是<b>自己这边</b>放掉的，不是被别人抢走的。 */
    static final int ABANDONED = -5;

    /**
     * 两次「补信号」之间的最小间隔，也是<b>被抢走/被拒绝</b>之后等待自动归还的宽限期。
     *
     * <p>别调太小：每次补信号都会让 SDK 在下一帧音频上重新 {@code requestAudioFocus()}，
     * 而申请 GAIN 会把车机当前持有焦点的音源（收音机、导航播报）顶掉。4 秒是「够快恢复」
     * 与「不来回抢」之间的折中。
     */
    public static final long RETRY_INTERVAL_MS = 4_000L;

    /**
     * 焦点是<b>自己放掉的</b>（{@code -5}）时的宽限期，比被抢走短得多。
     *
     * <p>区分的依据是 {@code -5} 的产生方式：SDK 只在 {@code stopPlayer()} 收尾和
     * {@code abandonAudioFocus()} 时报告它 —— 也就是本应用拆播放器、或断连收尾时<b>自己</b>把焦点
     * 还给了系统。这种情况下没有竞争对手在持有焦点，不存在「等它让出来」的必要，继续等满 4 秒只是
     * 白白把静音拉长。
     *
     * <p>实测这是断音的大头：一次上车日志里音乐「不持有焦点」的 5 段共 42.4s，其中 3 段（28.6s，
     * 67%）是由 {@code -5} 引起的，单段 6.3s / 9.9s / 12.5s。
     *
     * <p>取 1s 而不是 0：{@code abandon} 与紧随其后的重建之间确实可能隔几帧，留一点时间让
     * {@code createPlayer} 自己把闩打开（它有这个路径），能省掉一次无谓的补信号。轮询本身是 2 秒
     * 一次，所以实际恢复时间由「下一个轮询拍」决定，约等于 2s 而不是原来的 6~12s。
     */
    public static final long REARM_AFTER_ABANDON_MS = 1_000L;

    /**
     * 一次「卡住」最多补几次。
     *
     * <p>上限存在的意义：如果对方（车机的音频策略、正在通话的另一方）就是不给焦点，
     * 无限重试只会变成噪音和功耗。补满就停手，日志里留下明确的「放弃」痕迹。
     */
    public static final int MAX_STRIKES = 6;

    private AudioFocusPolicy() { }

    /**
     * 这个焦点值是否表示「现在没焦点、而且系统不会再主动通知我们」。
     *
     * <p><b>四种需要补信号的值</b>：
     * <ul>
     *   <li>{@code -1} 永久丢失：系统不会再还回来，必须自己重新申请；</li>
     *   <li>{@code -4} 申请被拒：SDK 没有任何重试；</li>
     *   <li>{@code -5} 已放弃：SDK 自己把某个音源的焦点还掉了（{@code stopPlayer} 分支）；</li>
     *   <li>{@link #NO_EVENT} 本音源<b>一次焦点事件都没收到过</b>。它曾经是断音最常见的一条：
     *       {@code AudioPlayerMgr.getKey()} 是 {@code focusGain_usage_contentType}，而
     *       {@code getAudioAttributesByType()} 返回 null 时所有音源都用同一套默认属性
     *       （{@code 1_1_2}）—— <b>通知音和音乐共用同一个焦点状态</b>，通知先播先拿到焦点，
     *       音乐建播放器时看到「已经有焦点」就不申请；等通知放完、把那个状态从表里移除，
     *       音乐接下来的每一帧就都是 {@code no focus, drop data}，而它自己从未收到过任何回调。
     *       音源身份拆分（{@link AudioFocusAttributionPolicy}）已经关掉了音乐/通知这一条，
     *       所以这一判据现在主要是兜底：通话音源仍与音乐同键（本次未动），以及播放器刚建好、
     *       还没来得及收到任何回调的窗口。</li>
     * </ul>
     *
     * <p>{@code -2}（暂时丢失）、{@code -3}（可 duck）、{@code 0}（正在申请）都不算：
     * 系统随后会回调 {@code 1} 把焦点还回来，SDK 自己会 {@code resume()}，我们不能抢在它前面插一脚。
     */
    public static boolean isNotHolding(int focus) {
        return focus == -1 || focus == -4 || focus == ABANDONED || focus == NO_EVENT;
    }

    /**
     * 是否该补一次 {@code allowGainAudioFocus()}。
     *
     * @param sessionUp          会话是否还在（没有会话就没有音频，别去动焦点）
     * @param musicPlaying       手机是否在放歌（{@code MusicInfo.isPlaying()}）。
     *                           这是唯一可靠的「应该有声音」信号：没有它就不该抢焦点，
     *                           否则用户只是连着没放歌，我们却把车机收音机顶掉了
     * @param lastFocus          该音源最近一次焦点回调（只统计本音源，见 IccoaManager 的按类型跟踪）
     * @param sinceLastFocusMs   距最近一次焦点回调的毫秒数
     * @param strikes            本轮已经补过几次
     * @param sinceLastAttemptMs 距上次补信号的毫秒数
     */
    public static boolean shouldRearm(boolean sessionUp,
                                      boolean musicPlaying,
                                      int lastFocus,
                                      long sinceLastFocusMs,
                                      int strikes,
                                      long sinceLastAttemptMs) {
        if (!sessionUp || !musicPlaying) return false;
        if (!isNotHolding(lastFocus)) return false;
        if (strikes >= MAX_STRIKES) return false;
        // 宽限期按「焦点是怎么没的」分档：
        //   · -5 是自己放掉的，没有对手要让，等满 4 秒只是把静音拉长；
        //   · -1/-4 是真被别人抢走或被策略拒绝，给系统一点时间，通话/播报结束后可能自己还回来，
        //     这时候抢一下只会把车机的收音机/导航顶掉，并诱发一轮新的 GAIN↔LOSS。
        long grace = lastFocus == ABANDONED ? REARM_AFTER_ABANDON_MS : RETRY_INTERVAL_MS;
        if (sinceLastFocusMs < grace) return false;
        return sinceLastAttemptMs >= RETRY_INTERVAL_MS;
    }

    /**
     * 收到新的焦点回调后，本轮的补信号次数怎么变。
     *
     * <p>只有<b>真的拿到焦点</b>（{@code 1}）才清零。刻意不在收到新的丢失/被拒回调时清零 ——
     * 否则「申请被拒（-4）→ 补信号 → 又被拒」会自己把计数清零，变成每 4 秒一次的无限重试。
     */
    public static int strikesAfterFocusEvent(int strikes, int focus) {
        return focus == 1 ? 0 : strikes;
    }
}
