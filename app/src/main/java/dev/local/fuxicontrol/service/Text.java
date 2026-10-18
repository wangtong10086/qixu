package dev.local.fuxicontrol.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

public final class Text {
    public static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
    public static void write(Path path,String text,OpenOption... options) throws IOException {
        Files.write(path,text.getBytes(StandardCharsets.UTF_8),options);
    }
}
