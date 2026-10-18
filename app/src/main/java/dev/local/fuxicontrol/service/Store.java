package dev.local.fuxicontrol.service;

import dev.local.fuxicontrol.core.Policy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

public final class Store {
    public final Path directory;
    private JSONObject policy;
    public Store(Path directory) throws Exception {
        this.directory = directory;
        Files.createDirectories(directory);
        Path file = directory.resolve("policy.json");
        policy = Files.exists(file) ? new JSONObject(Text.read(file)) : Policy.initial();
        Policy.validate(policy);
        if (!Files.exists(file)) atomic(file, policy.toString(2));
    }
    public synchronized JSONObject get() { return Policy.copy(policy); }
    public synchronized JSONObject replace(JSONObject next, boolean dryRun) throws Exception {
        dev.local.fuxicontrol.core.Profiles.sync(next);
        Policy.validate(next);
        next.put("revision", policy.getLong("revision") + 1);
        if (dryRun) return next;
        atomic(directory.resolve("policy.previous.json"), policy.toString(2));
        atomic(directory.resolve("policy.json"), next.toString(2));
        policy = Policy.copy(next);
        notifyAll();
        return get();
    }
    public synchronized JSONObject after(long revision) throws InterruptedException,org.json.JSONException {
        while (policy.getLong("revision") <= revision) wait();
        return get();
    }
    public static void atomic(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".new");
        Text.write(temp, content);
        Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
