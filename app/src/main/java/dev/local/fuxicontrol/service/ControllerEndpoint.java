package dev.local.fuxicontrol.service;

import android.os.*;
import dev.local.fuxicontrol.ipc.IController;
import java.io.*;
import java.util.Set;
import java.util.concurrent.*;
import org.json.JSONObject;

final class ControllerEndpoint extends IController.Stub {
    private record Session(int uid,Thread thread,ParcelFileDescriptor writer) {
        void stop() {thread.interrupt();try {writer.close();} catch(IOException e) {android.util.Log.w("QixuIPC","Pipe close",e);}}
    }
    private final ConcurrentHashMap<IBinder,Session> sessions=new ConcurrentHashMap<>();
    private final Semaphore slots=new Semaphore(48);
    private final Store store;
    private final Catalog catalog;
    private final Commands commands;
    private final SplashChannel splash;
    private final String signer;
    ControllerEndpoint(Store store,Catalog catalog,Commands commands,SplashChannel splash,String signer) {
        this.store=store;this.catalog=catalog;this.commands=commands;this.splash=splash;this.signer=signer;
    }
    @Override public void cancel(IBinder token) {
        Session session=sessions.get(token);
        if(session!=null) {
            if(session.uid()!=Binder.getCallingUid()) throw new SecurityException("Session owner mismatch");
            session.stop();
        }
    }
    @Override public synchronized ParcelFileDescriptor open(String input,IBinder token) {
        int uid=Binder.getCallingUid(),pid=Binder.getCallingPid();
        if(input.length()>262144) throw new IllegalArgumentException("Request exceeds 256 KiB");
        try {
        JSONObject request=new JSONObject(input);
        String command=request.getString("command");
        boolean ad=request.optString("channel").equals("splash");
        try {
            if(ad) {
                JSONObject identity=splash.identity(request.getString("package"));
                if(!identity.getBoolean("installed") || identity.getInt("uid")!=uid) throw new SecurityException("Ad package owner mismatch");
                if(!Set.of("watch","report").contains(command)) throw new SecurityException("Unsupported ad operation");
            } else {
                if(uid!=0 && uid!=1000 && !catalog.managerUid(uid,signer)) throw new SecurityException("Untrusted controller caller");
                if(uid==1000 && !Set.of("watch","event","status").contains(command)) throw new SecurityException("Restricted system caller");
            }
            if(sessions.values().stream().filter(s->s.uid()==uid).count()>=8 || !slots.tryAcquire()) {
                // Admission failure is a command response, not an unchecked Binder exception.
                ParcelFileDescriptor[] rejected=ParcelFileDescriptor.createReliablePipe();
                try(var output=new ParcelFileDescriptor.AutoCloseOutputStream(rejected[1])) {
                    output.write((new JSONObject().put("ok",false).put("error","IPC_BUSY: 栖序服务繁忙，请稍后再试；本次请求未提交").toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return rejected[0];
            }
            ParcelFileDescriptor[] pipe;
            try {pipe=ParcelFileDescriptor.createReliablePipe();}
            catch(IOException e) {slots.release();throw e;}
            Thread worker=new Thread(()->serve(request,uid,pid,token,pipe[1],ad),"qixu-ipc");
            Session session=new Session(uid,worker,pipe[1]);
            if(sessions.putIfAbsent(token,session)!=null) {pipe[0].close();pipe[1].close();slots.release();throw new IllegalArgumentException("Duplicate session");}
            worker.start();
            return pipe[0];
        } catch(IOException e) {throw new IllegalStateException("Cannot create IPC response",e);}
        catch(RuntimeException e) {throw e;}
        catch(Exception e) {throw new IllegalStateException("Cannot authorize IPC caller",e);}
        } catch(org.json.JSONException e) {throw new IllegalArgumentException("Invalid IPC request",e);}
    }
    private void serve(JSONObject request,int uid,int pid,IBinder token,ParcelFileDescriptor writer,boolean ad) {
        boolean linked=false;
        IBinder.DeathRecipient died=()-> {Session session=sessions.get(token);if(session!=null) session.stop();};
        try(var output=new PrintWriter(new OutputStreamWriter(new ParcelFileDescriptor.AutoCloseOutputStream(writer),java.nio.charset.StandardCharsets.UTF_8),true)) {
            token.linkToDeath(died,0);linked=true;
            try {
                if(ad) splash.serve(request,uid,pid,output);
                else if(request.getString("command").equals("watch")) {
                    long revision=-1;
                    while(!Thread.currentThread().isInterrupted()) {
                        JSONObject config=store.after(revision);
                        output.println(catalog.snapshot(config));
                        if(output.checkError()) break;
                        revision=config.getLong("revision");
                    }
                } else output.println(new JSONObject().put("ok",true).put("result",commands.execute(request)));
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            catch(Exception e) {output.println(new JSONObject().put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage()));}
        } catch(RemoteException e) {android.util.Log.d("QixuIPC","Caller exited");}
        catch(org.json.JSONException e) {android.util.Log.e("QixuIPC","Response encoding failed",e);}
        finally {if(linked) token.unlinkToDeath(died,0);sessions.remove(token);slots.release();}
    }
}
