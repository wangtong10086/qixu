package dev.local.fuxicontrol.service;

import android.content.Context;
import android.media.AudioManager;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Focus protects calls and buffering too; playback alone is not sufficient. */
public final class AudioActivity {
    private final AudioManager audio;
    private final Object service;
    private final Method focusStack;
    private final Method focusUid;
    private final Method focusLoss;
    private final Method playbackUid;
    private final Method playing;
    public AudioActivity(Context context) throws Exception {
        audio=context.getSystemService(AudioManager.class);
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"audio");
        service=Class.forName("android.media.IAudioService$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
        focusStack=Class.forName("android.media.IAudioService").getMethod("getFocusStack");
        focusUid=Class.forName("android.media.AudioFocusInfo").getMethod("getClientUid");
        focusLoss=Class.forName("android.media.AudioFocusInfo").getMethod("getLossReceived");
        playbackUid=android.media.AudioPlaybackConfiguration.class.getMethod("getClientUid");
        playing=android.media.AudioPlaybackConfiguration.class.getMethod("isActive");
    }
    public Set<Integer> activeUids() throws Exception {
        Set<Integer> result=new HashSet<>();
        for(Object focus:(java.util.List<?>)focusStack.invoke(service)) if((int)focusLoss.invoke(focus)==0) result.add((int)focusUid.invoke(focus));
        for(var playback:audio.getActivePlaybackConfigurations()) if((boolean)playing.invoke(playback))
            result.add((int)playbackUid.invoke(playback));
        return result;
    }
}
