package com.miauto.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * P2P 接口的收发字节计数 —— 用来判断「断连之前数据面是不是已经停了」。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>2026-10-23 22:58 那份报表里，两次异常断连的现场完全一致：页面在前台、Surface 有效、
 * 保活锁都持有、`P2pLinkProbe` 读到的组一直在 `5180MHz(ch36) GO=1 成员=1`、音频 `在响=true`。
 * 也就是说「**组在、锁在、状态好**」，然后 3 秒后会话就没了。
 *
 * <p>这里必须区分两种完全不同的根因：
 * <ul>
 *   <li><b>组在但数据面停了</b>（射频丢包、手机侧停推流、Wi-Fi 与蓝牙共存干扰）
 *       → 流量计数会在断连前若干秒掉到 0，SDK 只是心跳超时后收尸；</li>
 *   <li><b>数据面一直在跑</b>，SDK 仍然断
 *       → 问题在协议层（心跳包/鉴权/对端主动 disconnect），与 Wi-Fi 无关。</li>
 * </ul>
 *
 * <p>`WifiP2pManager` 不提供 RSSI / 吞吐，`dumpsys` 又要权限，所以在没有 adb 的前提下，
 * `/sys/class/net/<if>/statistics/{rx,tx}_bytes` 是唯一还能拿到的链路级证据。
 *
 * <h3>可用性</h3>
 *
 * <p>部分 ROM 的 SELinux 不允许 app 读 sysfs。读不到一律返回 {@code null}，
 * 心跳里就不出现这一段（**绝不因为读不到就打成 0**，那会把「没有观测」误判成「流量归零」）。
 */
final class P2pTrafficProbe {

    private static String iface = null;
    private static boolean probed = false;
    /** 是否走 /proc/net/dev（false = 走 sysfs）。实测这台车 sysfs 读不到，proc 兜底。 */
    private static boolean useProc = false;
    /** 是否走 TrafficStats.getRxBytes(iface)。Android 10+ 收紧 /proc/net 之后的接口级通道。 */
    private static boolean useTs = false;

    /**
     * 接口级通道全不可用时的兜底：改读 {@link android.net.TrafficStats} 的<b>全设备</b>计数。
     *
     * <p>实测（2026-10-24，小米平板 Android 14 / API 34）：{@code /sys/class/net/p2p0/statistics/*}
     * 被 SELinux 挡，{@code /proc/net/dev} 在 Android 10+ 已不对 app 开放 —— 两条接口级通道
     * 都拿不到，于是「流量」这一段在报表里<b>一次都没出现过</b>，等于没有观测。
     *
     * <p>全设备口径当然不专属于 P2P，但投屏会话是这段时间的主要流量来源，
     * 「断连前若干秒速率是否已经掉到 0」这个问题照样能判 —— 前提是日志必须写清口径。
     */
    private static boolean globalMode = false;
    private static boolean modeDecided = false;

    private static long lastRx = -1L;
    private static long lastTx = -1L;
    private static long lastAt = 0L;
    private static long baseRx = -1L;
    private static long baseTx = -1L;

    private P2pTrafficProbe() { }

    /**
     * 心跳用的一行流量描述。
     *
     * @return {@code "p2p0 rx=123KB/s tx=45KB/s（累计 12.3MB/4.5MB）"}；不可用时 {@code null}
     */
    static synchronized String rateLine() {
        // 取数通道只判定一次：sysfs 与 /proc/net/dev 都读不到就固定走全设备计数，
        // 免得每 5 秒一次心跳都去撞一遍注定失败的 IO。
        if (!modeDecided) {
            modeDecided = true;
            ensureIface();
            // 三条接口级通道（sysfs / /proc/net/dev / TrafficStats.getRxBytes(iface)）都试过仍读不到
            // 才降级到全设备口径 —— 这一句很重要：它在报表里留下「观测降级过」的痕迹，
            // 否则看到流量数字的人不知道它其实不是 P2P 专用口径。
            long probeRx = iface == null ? -1L : read("rx_bytes");
            if (probeRx < 0) {
                globalMode = true;
                // iface 为 null 说明连 /sys/class/net 都枚举不到（Android 12+ 收紧了 /sys 的部分访问），
                // 此时「按接口」的第三条通道自然也没法试 —— 这两种情况要说清楚，否则报表里
                // 「P2P 接口（null）」会被读成「有个叫 null 的接口」。
                android.util.Log.i("OPENHU", iface == null
                        ? "P2pTrafficProbe: 枚举不到 P2P 接口（/sys/class/net 不可读，Android 12+ 常见）"
                            + " → 改用全设备计数，心跳里的「流量」是全设备口径、不是 P2P 专用"
                        : "P2pTrafficProbe: P2P 接口（" + iface + "）统计三条通道都读不到"
                            + "（sysfs 被 SELinux 挡 / Android 10+ 禁止 app 读 /proc/net / TrafficStats 按接口不可用）"
                            + " → 改用全设备计数，心跳里的「流量」是全设备口径、不是 P2P 专用");
            } else {
                android.util.Log.i("OPENHU", "P2pTrafficProbe: 流量取数通道=" + sourceName());
            }
        }
        String name;
        long rx;
        long tx;
        if (globalMode) {
            rx = android.net.TrafficStats.getTotalRxBytes();
            tx = android.net.TrafficStats.getTotalTxBytes();
            if (rx < 0 || tx < 0) return null;
            name = "全设备";
        } else {
            name = iface;
            if (name == null) return null;
            rx = read("rx_bytes");
            tx = read("tx_bytes");
            if (rx < 0 || tx < 0) return null;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (baseRx < 0) {
            baseRx = rx;
            baseTx = tx;
        }
        String line;
        if (lastAt == 0L) {
            // 基线行顺带标出取数通道：报表里看到「/proc/net/dev:p2p0」就说明 sysfs 那条确实读不到，
            // 看到「全设备(TrafficStats)」就说明两条接口级通道都不可用、速率是全设备口径。
            line = name + " 基线（累计 " + mb(rx) + "/" + mb(tx) + "，源=" + sourceName() + "）";
        } else {
            long dt = now - lastAt;
            if (dt <= 0) return null;
            long drx = Math.max(0L, rx - lastRx);
            long dtx = Math.max(0L, tx - lastTx);
            line = name + " rx=" + kbps(drx, dt) + " tx=" + kbps(dtx, dt)
                    + "（累计 " + mb(rx) + "/" + mb(tx) + "）";
        }
        lastRx = rx;
        lastTx = tx;
        lastAt = now;
        return line;
    }

    /** 会话建立时调一次，让下一次心跳从「基线」开始重新算速率（避免跨会话的差值）。 */
    static synchronized void resetBaseline() {
        // 通道判定保留（globalMode 不重置）：它只取决于这台设备的权限，与会话无关。
        lastRx = -1L;
        lastTx = -1L;
        lastAt = 0L;
        baseRx = -1L;
        baseTx = -1L;
    }

    private static String ensureIface() {
        if (probed) return iface;
        probed = true;
        try {
            File net = new File("/sys/class/net");
            String[] names = net.list();
            if (names != null) {
                List<String> p2p = new ArrayList<>();
                for (String n : names) {
                    if (n != null && n.startsWith("p2p")) p2p.add(n);
                }
                // 优先 p2p0（多数平台），否则取第一个 p2p* 接口
                if (p2p.contains("p2p0")) {
                    iface = "p2p0";
                } else if (!p2p.isEmpty()) {
                    iface = p2p.get(0);
                }
            }
        } catch (Throwable ignored) {
            iface = null;
        }
        // 只枚举接口名，不再在这里校验「能不能读到计数」：接口名是 TrafficStats
        // 按接口查询的唯一入参，而可读性是 read() 每次自己判断的事。0.1.11.5 在这里
        // 因为 sysfs 与 /proc/net/dev 都读不到而把 iface 置回 null，等于连第三条通道也放弃了。
        return iface;
    }

    /** 累计收到的字节数；两条来源都试，按可用性选一条并记住。 */
    private static long read(String stat) {
        if (iface == null) return -1L;
        boolean rx = "rx_bytes".equals(stat);
        if (useProc) {
            long v = readProcDev(iface, rx);
            if (v >= 0) return v;
            useProc = false;      // proc 也读不到就退回 sysfs 再试一次
        }
        long v = readSys(stat);
        if (v < 0) {
            v = readProcDev(iface, rx);
            if (v >= 0) useProc = true;
        }
        if (v < 0) {
            // 第三条通道：TrafficStats 按接口查询。它走系统服务而不是 procfs/sysfs，
            // 是 Android 10+ 收紧 /proc/net 之后唯一还能拿到「接口级」计数的公开途径。
            v = readTrafficStats(iface, rx);
            if (v >= 0) useTs = true;
        }
        return v;
    }

    /** TrafficStats 按接口取累计字节；UNSUPPORTED(-1) 与抛异常都算不可用。 */
    private static long readTrafficStats(String name, boolean rx) {
        try {
            long v = rx ? android.net.TrafficStats.getRxBytes(name)
                        : android.net.TrafficStats.getTxBytes(name);
            return v < 0 ? -1L : v;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 当前数据来源，只用于在心跳里标注（报表里看到它就能判断取数通道是否可用）。 */
    static String sourceName() {
        if (globalMode) return "全设备(TrafficStats)：非 P2P 专用口径";
        if (iface == null) return "不可用";
        if (useTs) return "TrafficStats(iface):" + iface;
        return (useProc ? "/proc/net/dev:" : "/sys/class/net/:") + iface;
    }

    private static long readSys(String stat) {
        if (iface == null) return -1L;
        try {
            byte[] buf = new byte[64];
            java.io.FileInputStream in = new java.io.FileInputStream(
                    "/sys/class/net/" + iface + "/statistics/" + stat);
            try {
                int n = in.read(buf);
                if (n <= 0) return -1L;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    char c = (char) buf[i];
                    if (c == '\n' || c == '\r' || c == ' ' || c == '\t') break;
                    if (c < '0' || c > '9') return -1L;
                    sb.append(c);
                }
                return sb.length() == 0 ? -1L : Long.parseLong(sb.toString());
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 从 {@code /proc/net/dev} 里解析某个接口的累计字节数。
     *
     * <p>格式：{@code "  p2p0: 1234567 890 0 0 0 0 0 0 7654321 210 0 0 0 0 0 0"}
     * —— 冒号后第 1 个数是 rx_bytes，第 9 个数是 tx_bytes。
     */
    private static long readProcDev(String name, boolean rx) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream("/proc/net/dev"), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    if (!line.substring(0, colon).trim().equals(name)) continue;
                    String[] f = line.substring(colon + 1).trim().split("\\s+");
                    int idx = rx ? 0 : 8;
                    if (f.length <= idx) return -1L;
                    return Long.parseLong(f[idx]);
                }
            } finally {
                br.close();
            }
        } catch (Throwable t) {
            return -1L;
        }
        return -1L;
    }

    private static String kbps(long deltaBytes, long deltaMs) {
        double kbs = deltaBytes / 1024.0 / (deltaMs / 1000.0);
        return String.format(java.util.Locale.US, "%.0fKB/s", kbs);
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0);
    }
}
