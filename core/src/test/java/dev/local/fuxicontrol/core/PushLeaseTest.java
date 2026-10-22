package dev.local.fuxicontrol.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class PushLeaseTest {
 @Test public void expiryAndPackageScope(){
  String key="qixin.push.lease.com.example.test";System.setProperty(key,"100");
  try{assertTrue(PushLease.active("com.example.test",99));assertFalse(PushLease.active("com.example.other",99));assertFalse(PushLease.active("com.example.test",100));assertNull(System.getProperty(key));}
  finally{System.clearProperty(key);}
 }
}
