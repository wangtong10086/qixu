package dev.local.fuxicontrol.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Explicitly launched test activity; never used by the production manager. */
public final class SplashFailure extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text=new TextView(this);
        text.setText("栖序开屏异常恢复测试\n仅测试应用会退出");setContentView(text);
        if(getIntent().getBooleanExtra("crash",false)) new Handler(Looper.getMainLooper()).postDelayed(()->{
            throw new IllegalStateException("Qixu splash acceptance: intentional probe crash");
        },5000);
    }
}
