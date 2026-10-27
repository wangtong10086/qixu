package dev.local.fuxicontrol.core;

import java.util.List;

/** Versions describe inspected releases; runtime entrypoint checks decide compatibility. */
public final class SplashTargets {
    public static final int ADAPTER_REVISION = 3;
    public record Target(String packageName, long version, String certificate) {
        public boolean matches(long installedVersion, String signer) {
            return certificate.equals(signer);
        }
    }
    public static final List<Target> ALL = List.of(
        new Target("tv.danmaku.bili", 9150300L, "93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e"),
        new Target("com.tencent.qqmusic", 7508L, "bf3db8c6cbbf11104eb62fc20e9dfb27b37445d84bdb529241ed498b0044fa1e"),
        new Target("com.xingin.xhs", 9492803L, "dbf2ddfe68dc6c3d7bdbd1c70aae13993f50fa99b51d6f0c668a284ee9e6fdcd"),
        new Target("com.zhihu.android", 41108L, "bd8450557cb3965c051f1611d4286a5f029b909cae3de757ec152d0563c3f7fa"),
        new Target("com.taobao.taobao", 864L, "c5e9bc4c5cc7a34482c627cb9a76b88e30b509d0da3ba54a66274200e19c2743"),
        new Target("com.jingdong.app.mall", 102887L, "a5fdbfa8a769a9f2041c7f6706a52b8d981c0f12a6f10e989a9f583a43ed217d"),
        new Target("com.autonavi.minimap", 170100L, "b36c0b80cb2417e7fe4c3df01a2d163a7d8443604dd276a2bc1ab0d325102e3e"),
        new Target("com.taobao.idlefish", 525L, "e4c15e30502f8da0e878e0fe73877403b8d99f8f761809507a6512ecfed84909"),
        new Target("com.cainiao.wireless", 477L, "83f9f14c3432915c85c833915fe5f2db6de31c4ceb3bc92e33d341a527dbacdc"));
    public static Target find(String name) {
        return ALL.stream().filter(t -> t.packageName().equals(name)).findFirst().orElse(null);
    }
    private SplashTargets() {}
}
