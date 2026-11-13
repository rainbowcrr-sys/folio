package net.de5.rainbowcrrsys.folio;

/** Minimal JSON text-component construction, zero dependencies. */
public final class TextComponent {

    private TextComponent() {
    }

    public static String plain(String text) {
        return "{\"text\":\"" + escape(text) + "\"}";
    }

    /** The only bilingual user-facing string in this milestone: the version gate. */
    public static String bilingual(String english, String chinese) {
        return plain(english + "\n" + chinese);
    }

    public static String versionGate() {
        return bilingual(
                "This server requires Minecraft Java 26.1 or newer.",
                "本服务器需要 Minecraft Java 26.1 或更高版本。");
    }

    private static String escape(String raw) {
        StringBuilder b = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}
