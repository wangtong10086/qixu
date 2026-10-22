package dev.local.fuxicontrol.core;
/** Process-local system_server contract, inaccessible to application JVM properties. */
public final class PushLease {
 public static boolean active(String pkg,long now){
  String key="qixin.push.lease."+pkg,value=System.getProperty(key);
  if(value==null)return false;
  long expiry=Long.parseLong(value);
  if(now<expiry)return true;
  System.getProperties().remove(key,value);return false;
 }
}
