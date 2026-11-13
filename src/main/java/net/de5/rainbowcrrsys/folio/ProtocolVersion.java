package net.de5.rainbowcrrsys.folio;

import java.util.Optional;

/**
 * The 26.x drops supported by this core. Protocol numbers are exact-match values carried
 * in the Handshake packet; they are not minimum-version semantics.
 *
 * <p>Adding 26.4 is a registration change: one enum constant plus one difference-map entry
 * in {@link PacketIds}. The state machine and codec stay untouched.
 */
public enum ProtocolVersion {
    V26_1(775, "26.1"),
    V26_2(776, "26.2"),
    V26_3(777, "26.3");

    public static final int MINIMUM = 775;

    private final int protocol;
    private final String display;

    ProtocolVersion(int protocol, String display) {
        this.protocol = protocol;
        this.display = display;
    }

    public int protocol() {
        return protocol;
    }

    public String display() {
        return display;
    }

    public static Optional<ProtocolVersion> fromProtocol(int protocol) {
        for (ProtocolVersion v : values()) {
            if (v.protocol == protocol) return Optional.of(v);
        }
        return Optional.empty();
    }

    /** Resolves a display name such as {@code 26.1}; falls back to the latest registered drop. */
    public static ProtocolVersion fromName(String name) {
        if (name != null && !name.isBlank()) {
            for (ProtocolVersion v : values()) {
                if (v.display.equals(name.trim()) || v.name().equals(name.trim())) return v;
            }
        }
        return V26_3;
    }
}
