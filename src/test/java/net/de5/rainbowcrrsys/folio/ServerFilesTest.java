package net.de5.rainbowcrrsys.folio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vanilla-compatible on-disk layout, tested against an isolated temp directory. */
class ServerFilesTest {

    @Test
    void createsTheFullServerDirectoryLayout() throws Exception {
        Path dir = Files.createTempDirectory("folio-files-");
        ServerFiles.ensure(dir);

        assertFile(dir, "server.properties");
        for (String name : new String[]{"ops.json", "whitelist.json", "banned-players.json",
                "banned-ips.json", "usercache.json"}) {
            assertFile(dir, name);
            assertTrue("[]".equals(Files.readString(dir.resolve(name), StandardCharsets.UTF_8).trim()),
                    name + " must be an empty JSON array");
        }
        for (String world : new String[]{
                "world/dimensions/minecraft/overworld/region",
                "world/dimensions/minecraft/the_nether/region",
                "world/dimensions/minecraft/the_end/region",
                "world/players/data",
                "world/players/advancements",
                "world/players/stats",
                "world/data",
                "world/datapacks",
                "world/resourcepacks"}) {
            assertTrue(Files.isDirectory(dir.resolve(world)), world + " must exist");
        }

        String props = Files.readString(dir.resolve("server.properties"), StandardCharsets.UTF_8);
        assertTrue(props.contains("server-port=25565"), "vanilla port key");
        assertTrue(props.contains("online-mode=true"), "vanilla online-mode key");
        assertTrue(props.contains("level-name=world"), "vanilla level key");
        assertTrue(props.contains("config.yaml"), "must document the real config source");
    }

    @Test
    void neverOverwritesOperatorEdits() throws Exception {
        Path dir = Files.createTempDirectory("folio-files-");
        Path props = dir.resolve("server.properties");
        Files.writeString(props, "# my custom properties\nmotd=edited\n", StandardCharsets.UTF_8);

        ServerFiles.ensure(dir);

        assertEquals("# my custom properties\nmotd=edited\n",
                Files.readString(props, StandardCharsets.UTF_8));
    }

    @Test
    void isIdempotent() throws Exception {
        Path dir = Files.createTempDirectory("folio-files-");
        ServerFiles.ensure(dir);
        ServerFiles.ensure(dir);
        assertFile(dir, "server.properties");
        assertFile(dir, "ops.json");
        assertTrue(Files.isDirectory(dir.resolve("world/dimensions/minecraft/overworld/region")));
    }

    private static void assertFile(Path root, String name) {
        assertTrue(Files.isRegularFile(root.resolve(name)), name + " must exist");
    }
}
