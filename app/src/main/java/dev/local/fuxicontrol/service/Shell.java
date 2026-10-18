package dev.local.fuxicontrol.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Shell {
    public static String run(String... args) throws IOException,InterruptedException {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        int status=process.waitFor();
        if(status!=0) throw new IOException(args[0]+" exited "+status+": "+text);
        return text;
    }
}
