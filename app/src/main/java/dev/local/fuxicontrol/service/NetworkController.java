package dev.local.fuxicontrol.service;

import java.nio.file.Files;
import java.nio.file.Path;

/** Android uses a non-overridable, single-program root cgroup attachment on this ROM. */
public final class NetworkController {
    private static final String BASE="/data/adb/fuxicontrol/";
    private boolean active;
    public synchronized void ensure() throws Exception {
        if(!Files.exists(Path.of("/sys/fs/bpf/fuxicontrol/progs/fuxi_upload")))
            Shell.run("sh",BASE+"network-start.sh");
        Shell.run(BASE+"networkctl","attach");
        String status=Shell.run(BASE+"networkctl","status");
        if(status.lines().filter(line->line.endsWith("attached=true")).count()!=2)
            throw new IllegalStateException("Network programs are not both attached");
        active=true;
    }
    public synchronized void restore() throws Exception {
        if(active || Files.exists(Path.of("/sys/fs/bpf/fuxicontrol/original_0"))) {
            Shell.run(BASE+"networkctl","restore");active=false;
            String status=Shell.run(BASE+"networkctl","status");
            if(status.lines().filter(line->line.endsWith("attached=false")).count()!=2)
                throw new IllegalStateException("Cannot unpin network programs while attached");
            Shell.run("rm","-rf","/sys/fs/bpf/fuxicontrol");
        }
    }
}
