package dev.local.fuxicontrol.probe;

final class Datagrams {
    static void run(Main activity,String mode,boolean ipv6) {
        new Thread(()-> {
            try(var socket=new java.net.DatagramSocket()) {
                socket.connect(java.net.InetAddress.getByName(ipv6?"::1":"127.0.0.1"),ipv6?39184:39183);
                socket.setSoTimeout(1000);
                byte[] request=mode.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                socket.send(new java.net.DatagramPacket(request,request.length));
                byte[] data=new byte[1200];var packet=new java.net.DatagramPacket(data,data.length);
                long before=android.os.SystemClock.elapsedRealtime(),bytes=0;
                while(android.os.SystemClock.elapsedRealtime()-before<20000) {
                    if(mode.equals("upload")) {socket.send(packet);bytes+=data.length;Thread.sleep(1);}
                    else if(mode.equals("download")) {
                        try {packet.setLength(data.length);socket.receive(packet);bytes+=packet.getLength();}
                        catch(java.net.SocketTimeoutException interval) { /* Count a quiet interval as zero bytes. */ }
                    } else throw new IllegalArgumentException("Unknown datagram test");
                }
                long elapsed=android.os.SystemClock.elapsedRealtime()-before;
                var result=new org.json.JSONObject().put("protocol","udp").put("ipv6",ipv6).put("test",mode).put("bytes",bytes).put("elapsedMs",elapsed).put("bps",bytes*8000/elapsed);
                java.nio.file.Files.write(activity.getFilesDir().toPath().resolve("datagram.json"),result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                android.util.Log.i("FuxiProbe",result.toString());
            } catch(Exception failure) {android.util.Log.e("FuxiProbe","Datagram test failed",failure);}
        },"DatagramProbe").start();
    }
}
