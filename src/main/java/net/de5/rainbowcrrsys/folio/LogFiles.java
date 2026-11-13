package net.de5.rainbowcrrsys.folio;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

/**
 * Mirrors every {@code System.Logger} record into {@code logs/latest.log}.
 *
 * <p>The JDK's default System.Logger backend is java.util.logging, so attaching one handler
 * to the root logger captures all core logging without touching the call sites. The file is
 * truncated on each start (vanilla behaviour), and no {@code .lck} sidecar is produced.
 */
public final class LogFiles {

    private static boolean installed;

    private LogFiles() {
    }

    public static synchronized void install() {
        if (installed) return;
        try {
            Path dir = Paths.get("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("latest.log");
            Handler handler = new StreamHandler(
                    new FileOutputStream(file.toFile(), false), new SimpleFormatter()) {
                @Override
                public synchronized void publish(LogRecord record) {
                    super.publish(record);
                    flush();
                }
            };
            handler.setLevel(Level.ALL);
            Logger.getLogger("").addHandler(handler);
            installed = true;
        } catch (IOException e) {
            System.getLogger("folio.log").log(System.Logger.Level.WARNING,
                    "could not open logs/latest.log", e);
        }
    }
}
