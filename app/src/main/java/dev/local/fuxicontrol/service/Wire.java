package dev.local.fuxicontrol.service;

import android.os.*;
import android.system.*;
import dev.local.fuxicontrol.ipc.IController;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Binder authenticates callers; anonymous pipes carry large responses without Binder size limits. */
public final class Wire {
    public static final class CommandException extends IOException {
        public CommandException(String message) {super(message);}
    }
    public static final class Connection implements AutoCloseable {
        private final IController endpoint;
        private final Binder lifetime=new Binder();
        private ParcelFileDescriptor descriptor;
        private BufferedReader input;
        private int timeout=15000;
        private final boolean splash;
        Connection(boolean splash) throws IOException {
            this.splash=splash;
            try(BrokerConnection broker=new BrokerConnection()) {
                Bundle result=broker.call("get",null);
                if(result==null || result.getInt("protocol")!=1 || result.getBinder("controller")==null)
                    throw new IOException("栖序服务未连接");
                endpoint=IController.Stub.asInterface(result.getBinder("controller"));
            } catch(ReflectiveOperationException e) {throw new IOException("无法发现栖序 Binder 服务",e);}
        }
        public void setSoTimeout(int millis) {timeout=millis;}
        void send(JSONObject request) throws IOException,org.json.JSONException {
            if(descriptor!=null) throw new IllegalStateException("One request per connection");
            if(splash) request.put("channel","splash");
            try {descriptor=endpoint.open(request.toString(),lifetime);}
            catch(RemoteException e) {throw new IOException("栖序 Binder 已断开；写操作未自动重试",e);}
            var raw=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            input=new BufferedReader(new InputStreamReader(new FilterInputStream(raw) {
                private void ready() throws IOException {
                    StructPollfd poll=new StructPollfd();poll.fd=descriptor.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;
                    try {if(Os.poll(new StructPollfd[]{poll},timeout==0?-1:timeout)==0) throw new IOException("栖序响应超时；操作可能已执行，请查询状态");}
                    catch(ErrnoException e) {throw new IOException(e);}
                }
                @Override public int read(byte[] b,int off,int len) throws IOException {ready();return in.read(b,off,len);}
                @Override public int read() throws IOException {ready();return in.read();}
            },StandardCharsets.UTF_8));
        }
        @Override public void close() throws IOException {
            try {endpoint.cancel(lifetime);} catch(RemoteException e) { /* Dead endpoint has already released its workers. */ }
            if(input!=null) input.close();
        }
    }
    public static Connection connect() throws IOException {return new Connection(false);}
    public static Connection splash() throws IOException {return new Connection(true);}
    public static JSONObject call(JSONObject request) throws IOException,org.json.JSONException {
        try(Connection connection=connect()) {
            send(connection,request);String line=reader(connection).readLine();
            if(line==null) throw new IOException("Controller disconnected before responding");
            JSONObject response=new JSONObject(line);
            if(!response.getBoolean("ok")) throw new CommandException(response.getString("error"));
            return response.getJSONObject("result");
        }
    }
    public static BufferedReader reader(Connection connection) {return connection.input;}
    public static void send(Connection connection,JSONObject value) throws IOException,org.json.JSONException {connection.send(value);}
}
