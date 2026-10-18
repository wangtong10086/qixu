package dev.local.fuxicontrol.service;

/** Loopback-only fixture. Never contacts a third-party endpoint. */
public final class NetworkFixture {
    public static void main(String[] args) throws Exception {
        try(var server=new java.net.ServerSocket(39182,8,java.net.InetAddress.getByName("127.0.0.1"))) {
            System.out.println("Fixture listening on loopback");
            while(true) {
                var socket=server.accept();
                new Thread(()-> {
                    try(socket) {
                        socket.setSoTimeout(25000);
                        var input=new java.io.DataInputStream(socket.getInputStream());
                        String mode=input.readUTF();long start=System.nanoTime();long total=0;
                        byte[] data=new byte[16384];
                        if(mode.equals("download")) {
                            while(System.nanoTime()-start<20_000_000_000L) {socket.getOutputStream().write(data);total+=data.length;}
                        } else if(mode.equals("upload")) {
                            int count;while((count=input.read(data))!=-1)total+=count;
                        } else throw new IllegalArgumentException("Unknown fixture mode");
                        System.out.println("Completed "+mode+" bytes="+total);
                    } catch(Exception e) {e.printStackTrace();}
                }).start();
            }
        }
    }
}
