package net.de5.rainbowcrrsys.folio;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Offline mode: no encryption, UUIDv3 derived from {@code OfflinePlayer:<name>}. */
public final class OfflineAuth implements AuthProvider {

    @Override
    public boolean isOnline() {
        return false;
    }

    @Override
    public UUID profileUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
