package dev.local.fuxicontrol.probe;

public final class Isolated extends android.app.Service {
    @Override public android.os.IBinder onBind(android.content.Intent intent) {
        return new android.os.Binder() {
            @Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags) {
                if(code!=1) return false;
                reply.writeNoException();reply.writeInt(android.os.Process.myUid());
                return true;
            }
        };
    }
}
