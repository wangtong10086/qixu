package dev.local.fuxicontrol.service;

/** Disposable loopback fixture for UDP policer validation. */
public final class DatagramFixture {
    public static void main(String[] args) throws Exception {
        if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
        for(int family:new int[]{4,6}) new Thread(()-> {
            try(var socket=new java.net.DatagramSocket(new java.net.InetSocketAddress(family==4?"127.0.0.1":"::1",family==4?39183:39184))) {
                System.out.println("UDP ready IPv"+family);
                byte[] data=new byte[1200];var packet=new java.net.DatagramPacket(data,data.length);
                while(true) {
                    socket.setSoTimeout(0);packet.setLength(data.length);socket.receive(packet);
                    String mode=new String(packet.getData(),0,packet.getLength(),java.nio.charset.StandardCharsets.UTF_8);
                    long start=System.nanoTime(),bytes=0;socket.setSoTimeout(1000);
                    if(mode.equals("download")) {
                        var output=new java.net.DatagramPacket(data,data.length,packet.getSocketAddress());
                        while(System.nanoTime()-start<20_000_000_000L) {socket.send(output);bytes+=data.length;Thread.sleep(1);}
                    } else if(mode.equals("upload")) {
                        while(System.nanoTime()-start<21_000_000_000L) {
                            try {packet.setLength(data.length);socket.receive(packet);bytes+=packet.getLength();}
                            catch(java.net.SocketTimeoutException interval) { /* Keep measuring the fixed window. */ }
                        }
                    } else throw new IllegalArgumentException("Unknown datagram test");
                    System.out.println("UDP IPv"+family+" "+mode+" bytes="+bytes+" elapsedMs="+(System.nanoTime()-start)/1000000);
                }
            } catch(Exception failure) {throw new RuntimeException(failure);}
        },"UDP"+family).start();
    }
}
