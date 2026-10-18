package dev.local.fuxicontrol.service;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class Wire {
    public static final class CommandException extends IOException {
        public CommandException(String message) { super(message); }
    }
    public static final String ADDRESS = "fuxicontrol.v1";
    public static LocalSocket connect() throws IOException {
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(ADDRESS));
        if (socket.getPeerCredentials().getUid() != 0) {
            socket.close(); throw new SecurityException("Controller is not owned by root");
        }
        return socket;
    }
    public static JSONObject call(JSONObject request) throws IOException,org.json.JSONException {
        try(LocalSocket socket = connect()) {
            socket.setSoTimeout(15000);
            send(socket,request);
            String line = reader(socket).readLine();
            if (line == null) throw new IOException("Controller disconnected before responding");
            JSONObject response = new JSONObject(line);
            if (!response.getBoolean("ok")) throw new CommandException(response.getString("error"));
            return response.getJSONObject("result");
        }
    }
    public static BufferedReader reader(LocalSocket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
    }
    public static void send(LocalSocket socket, JSONObject value) throws IOException {
        OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8);
        out.write(value.toString());out.write('\n');out.flush();
    }
}
