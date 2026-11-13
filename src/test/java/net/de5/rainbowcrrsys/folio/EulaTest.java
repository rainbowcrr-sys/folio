package net.de5.rainbowcrrsys.folio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two-start EULA gate, tested against explicit temporary files. */
class EulaTest {

    @Test
    void firstStartWritesEulaFalseAndRefuses() throws Exception {
        Path dir = Files.createTempDirectory("folio-eula-");
        Path file = dir.resolve("eula.txt");

        assertFalse(Eula.ensure(file));
        assertTrue(Files.isRegularFile(file), "eula.txt must be written on the first start");

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("eula=false"), "default must be false");
        assertTrue(text.contains("aka.ms/MinecraftEULA"), "must point at the EULA");
        assertTrue(text.contains("#"), "must carry explanatory comments");
    }

    @Test
    void existingFalseIsLeftUntouchedAndRefuses() throws Exception {
        Path dir = Files.createTempDirectory("folio-eula-");
        Path file = dir.resolve("eula.txt");
        String original = "# custom note\neula=false\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        assertFalse(Eula.ensure(file));
        assertTrue(original.equals(Files.readString(file, StandardCharsets.UTF_8)),
                "an existing file must never be overwritten");
    }

    @Test
    void trueAllowsStartup() throws Exception {
        Path dir = Files.createTempDirectory("folio-eula-");
        Path file = dir.resolve("eula.txt");
        Files.writeString(file, "eula=true\n", StandardCharsets.UTF_8);
        assertTrue(Eula.ensure(file));
    }

    @Test
    void toleratesCaseWhitespaceAndComments() throws Exception {
        Path dir = Files.createTempDirectory("folio-eula-");
        Path file = dir.resolve("eula.txt");
        Files.writeString(file, "# agreement\n\n  EULA   =   TRUE   \n", StandardCharsets.UTF_8);
        assertTrue(Eula.ensure(file));
    }

    @Test
    void blankValueOrUnrelatedKeysRefuse() throws Exception {
        Path dir = Files.createTempDirectory("folio-eula-");
        Path file = dir.resolve("eula.txt");
        Files.writeString(file, "eula=\naccepted=true\n", StandardCharsets.UTF_8);
        assertFalse(Eula.ensure(file));
    }

    @Test
    void bomIsTolerated() throws Exception {
        Path dir = Files.createTempDirectory("folio-eula-");
        Path file = dir.resolve("eula.txt");
        Files.writeString(file, "\uFEFFeula=true\n", StandardCharsets.UTF_8);
        assertTrue(Eula.ensure(file));
    }
}
