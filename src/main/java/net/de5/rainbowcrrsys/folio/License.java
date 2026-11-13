package net.de5.rainbowcrrsys.folio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Writes the Apache-2.0 license text next to the working directory on first start.
 *
 * <p>The text is bundled inside the shaded jar as {@code /LICENSE.txt}, so a downloaded
 * jar always carries its own license. A plain-text {@code 许可.txt} is also committed at the
 * repository root. Neither step requires user interaction (no EULA acceptance flow).
 */
public final class License {

    private static final String FILE_NAME = "许可.txt";

    private License() {
    }

    public static void ensure() {
        Path target = Paths.get(FILE_NAME);
        if (Files.exists(target)) return;
        try (InputStream in = License.class.getResourceAsStream("/LICENSE.txt")) {
            if (in == null) return;
            Files.write(target, in.readAllBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.getLogger("folio").log(System.Logger.Level.WARNING,
                    "could not write " + FILE_NAME, e);
        }
    }
}
