package dev.local.fuxicontrol.service;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Audit {
    private final Path file;
    public Audit(Path directory) { file=directory.resolve("audit.jsonl"); }
    public synchronized void record(String event, JSONObject metadata) throws Exception {
        if (Files.exists(file) && Files.size(file)>4*1024*1024)
            Files.move(file,file.resolveSibling("audit.previous.jsonl"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Text.write(file,new JSONObject().put("time",System.currentTimeMillis()).put("event",event)
                .put("data",metadata).toString()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    public org.json.JSONArray tail() throws IOException,org.json.JSONException {
        if (!Files.exists(file)) return new org.json.JSONArray();
        var lines=Files.readAllLines(file);
        var result=new org.json.JSONArray();
        for(String line:lines.subList(Math.max(0,lines.size()-100),lines.size())) result.put(new JSONObject(line));
        return result;
    }
}
