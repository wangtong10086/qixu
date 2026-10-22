package dev.local.fuxicontrol.ipc;
import android.os.ParcelFileDescriptor;
interface IController {
    void cancel(IBinder lifetime);
    ParcelFileDescriptor open(String request, IBinder lifetime);
}
