package dev.local.fuxicontrol.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Discovery only. Root owns execution and checks every caller independently. */
public final class BrokerProvider extends ContentProvider {
    public static final String AUTHORITY="dev.local.fuxicontrol.ipc";
    private IBinder controller;
    @Override public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method,String arg,Bundle extras) {
        if(method.equals("register")) {
            if(Binder.getCallingUid()!=0) throw new SecurityException("Root registration required");
            IBinder next=extras.getBinder("controller");
            try { next.linkToDeath(()-> { synchronized(this) {if(controller==next) controller=null;} },0); }
            catch(RemoteException e) {throw new IllegalStateException("Controller died during registration",e);}
            controller=next;
        } else if(!method.equals("get")) throw new IllegalArgumentException("Unknown IPC discovery operation");
        Bundle result=new Bundle();result.putBinder("controller",controller);result.putInt("protocol",1);return result;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) {throw new UnsupportedOperationException();}
    @Override public String getType(Uri u) {return null;}
    @Override public Uri insert(Uri u,ContentValues v) {throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a) {throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a) {throw new UnsupportedOperationException();}
}
