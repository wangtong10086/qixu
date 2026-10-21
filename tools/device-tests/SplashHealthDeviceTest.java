import android.content.Context;
import dev.local.fuxicontrol.core.SplashHealth;
import dev.local.fuxicontrol.service.AndroidContext;
import dev.local.fuxicontrol.service.SplashChannel;
import dev.local.fuxicontrol.service.Store;
import dev.local.fuxicontrol.service.Text;
import java.nio.file.Path;
import org.json.JSONObject;

/** Exercises production exit-history processing in an isolated store, without registering
 * a test signer/package in the production adapter list or touching the live policy. */
public final class SplashHealthDeviceTest {
    public static void main(String[] args) throws Exception {
        String name="dev.local.fuxicontrol.probe";
        Context context=AndroidContext.system();
        var packageInfo=context.getPackageManager().getPackageInfo(name,0);
        JSONObject info=new JSONObject().put("version",packageInfo.getLongVersionCode()).put("updated",packageInfo.lastUpdateTime);
        var channel=new SplashChannel(new Store(Path.of("/data/local/tmp/qixu-splash-health-acceptance")),context);
        var keyMethod=SplashChannel.class.getDeclaredMethod("installation",JSONObject.class);keyMethod.setAccessible(true);
        String key=(String)keyMethod.invoke(channel,info);
        var healthField=SplashChannel.class.getDeclaredField("health");healthField.setAccessible(true);
        SplashHealth health=(SplashHealth)healthField.get(channel);
        String boot=Text.read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        int pid=Integer.parseInt(args[1]);
        var begin=SplashChannel.class.getDeclaredMethod("beginWatch",String.class,JSONObject.class,int.class);begin.setAccessible(true);
        begin.invoke(channel,name,info,pid);
        switch(args[0]) {
            case "arm" -> health.arm(name,key,pid,boot,System.currentTimeMillis());
            case "retry" -> health.retry(name,key);
            case "inspect" -> { }
            default -> throw new IllegalArgumentException("Expected arm, inspect or retry");
        }
        var save=SplashChannel.class.getDeclaredMethod("saveHealth");save.setAccessible(true);save.invoke(channel);
        System.out.println(health.status(name,key));
    }
}
