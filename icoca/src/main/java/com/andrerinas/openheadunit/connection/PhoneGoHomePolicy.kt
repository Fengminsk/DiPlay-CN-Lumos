package com.andrerinas.openheadunit.connection

/**
 * Whether the head unit should follow the phone to its launcher when the ICCOA link reports
 * `UCAR_DEVICE_GO_TO_HOME` (state 6), or ignore that particular report.
 *
 * Following the phone home is a real feature — the driver presses Home on the phone, the mirror
 * steps aside, and this is the only on-screen way back to the car UI — so the rule must suppress
 * the *bogus* reports without touching the genuine one.
 *
 * Two kinds of bogus report. Counted in DiAuto-report-20261021-130605, which holds 24 GO_TO_DESKTOP
 * reports — 18 of them actually moved the car UI — and, the reason either kind survives at all,
 * **no** paired state 7 whatsoever:
 *
 *  1. **An echo of our own rebind.** The mirror re-attaches the decoder every time it comes back (a
 *     new Surface, a fresh `startCast()`), and the phone sometimes answers *that* instead of a Home
 *     press. Seven of the twenty-four land 965–2125 ms after a cast (re)start, five of them inside
 *     one twelve-second stretch — the exact shape of the reported loop: honour one, the car leaves the
 *     mirror, the driver taps the icon, the mirror rebinds, the phone echoes again. Round trips with
 *     no way out. The other seventeen land 3.9 s to 292 s after a cast start; the log cannot separate
 *     a slow echo from a real Home press, which is why the rule is a bounded window and not "follow
 *     everything" or "follow nothing".
 *  2. **A repeat of one report.** Six inside ten seconds at 1792204328962 → 1792204338939 while the
 *     mirrored phone was demonstrably still being driven — its touch back-channel had just come up
 *     and the SDK had rendered a frame. One report is enough to move the car UI; the rest only fight
 *     the driver's next tap.
 *
 * So: ignore anything inside the settle window after the cast (re)started, and after acting on a
 * report ignore further ones until the driver has had time to come back and the phone has had time
 * to say it returned. Both are time-based because the protocol gives us nothing better — every
 * observed state 6 carries `param = -1`, so no report ever identifies itself as the real thing.
 *
 * Pure and unit-tested; `IcocaBridge` owns the timestamps and emits the event.
 */
object PhoneGoHomePolicy {

    /**
     * How long after the cast (re)started a GO_TO_DESKTOP is still considered our own echo. The
     * measured echoes span 965–2125 ms; this leaves a slower phone some slack without growing so
     * large that a Home press shortly after connecting gets swallowed.
     */
    const val CAST_SETTLE_MS = 5_000L

    /**
     * Minimum spacing between two honoured reports. Must exceed the ~2 s the driver spends tapping
     * back into the mirror (a shorter window leaves them in the bounce loop), and stay small enough
     * that a deliberate second Home press still works.
     */
    const val GO_HOME_MIN_INTERVAL_MS = 10_000L

    /** The two directions of the phone's mirror-state announcement. */
    enum class Event { GO_TO_DESKTOP, BACK_TO_FOREGROUND }

    enum class Ignore {
        /** The phone came back to its app: nothing to do, and the next report is judged fresh. */
        BACK_TO_FOREGROUND,

        /** Too soon after we re-attached the decoder to be anything but an echo of it. */
        CAST_ECHO,

        /** We already followed a report from this same phone; this is a re-announcement. */
        RATE_LIMITED,
    }

    data class Result(
        val goHome: Boolean,
        /** Set only when [goHome] is false, for the log. */
        val ignored: Ignore? = null,
        /** Reason text for the log, already explaining what was not done. */
        val detail: String? = null,
    )

    /**
     * @param now monotonic clock.
     * @param lastGoHomeAt when a report was last honoured, or null if never. Nulling this after a
     *        [Event.BACK_TO_FOREGROUND] is what lets the next real Home press act immediately.
     * @param lastCastStartedAt when the cast last (re)started, or null if it never did.
     */
    fun decide(
        event: Event,
        now: Long,
        lastGoHomeAt: Long?,
        lastCastStartedAt: Long?,
    ): Result {
        if (event == Event.BACK_TO_FOREGROUND) {
            return Result(
                goHome = false,
                ignored = Ignore.BACK_TO_FOREGROUND,
                detail = "手机已回到投屏界面（state=7）→ 车机不动作，并撤销「已跟随回桌面」的记录",
            )
        }

        lastCastStartedAt?.let {
            val since = now - it
            if (since in 0 until CAST_SETTLE_MS) {
                return Result(
                    goHome = false,
                    ignored = Ignore.CAST_ECHO,
                    detail = "手机报「回桌面」距本次重新绑定出画仅 ${since}ms（< ${CAST_SETTLE_MS}ms）" +
                            "→ 判定为车机换面引起的回声，车机不动作",
                )
            }
        }

        lastGoHomeAt?.let {
            val since = now - it
            if (since in 0 until GO_HOME_MIN_INTERVAL_MS) {
                return Result(
                    goHome = false,
                    ignored = Ignore.RATE_LIMITED,
                    detail = "手机在 ${since}ms 前已报过一次「回桌面」且期间没有回到投屏" +
                            "（< ${GO_HOME_MIN_INTERVAL_MS}ms）→ 视为同一次重复上报，车机不动作",
                )
            }
        }

        return Result(goHome = true)
    }
}
