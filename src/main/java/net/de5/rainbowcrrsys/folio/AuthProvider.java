package net.de5.rainbowcrrsys.folio;

import java.util.UUID;

/** Authentication strategy chosen once per connection at handshake time. */
public interface AuthProvider {

    boolean isOnline();

    /** Profile UUID used when no session lookup happens. */
    UUID profileUuid(String name);
}
