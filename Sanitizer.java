import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

public class Sanitizer {
    public static void main(String[] args) throws IOException {
        Path start = Paths.get("src/main/java");
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    sanitize(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void sanitize(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);

        // 1. Remove BOM (EF BB BF)
        int start = 0;
        if (bytes.length >= 3 &&
                (bytes[0] & 0xFF) == 0xEF &&
                (bytes[1] & 0xFF) == 0xBB &&
                (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
            System.out.println("Removed BOM from: " + file);
        }

        // 2. Filter invalid characters
        // We will rebuild the byte array, keeping only valid bytes.
        // For simplicity, we keep ASCII printable (32-126), tabs (9), newlines (10,
        // 13).
        // Extended characters (accents) will be stripped or replaced to be safe.
        // Actually, let's just keep valid UTF-8 but stripped of control chars.

        // Better approach for "illegal start of expression" often caused by visible
        // 'phantom' spaces (NBSP):
        // Replace 0xA0 (NBSP) with 0x20 (Space).

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        for (int i = start; i < bytes.length; i++) {
            byte b = bytes[i];
            int unsigned = b & 0xFF;

            // Check for NBSP (0xC2 0xA0 in UTF-8)
            if (unsigned == 0xC2 && i + 1 < bytes.length && (bytes[i + 1] & 0xFF) == 0xA0) {
                buffer.write(' '); // Replace with space
                i++; // Skip next byte
                continue;
            }

            // Allow standard ASCII
            if (unsigned >= 32 && unsigned <= 126) {
                buffer.write(b);
            }
            // Allow Tabs, CR, LF
            else if (unsigned == 9 || unsigned == 10 || unsigned == 13) {
                buffer.write(b);
            }
            // Allow Spanish accents and common symbols in UTF-8 (multibyte)
            // This is complex to filter byte-by-byte.
            // LET'S GO AGGRESSIVE: ONLY ASCII. Comments might lose accents but code will
            // compile.
            else {
                // Skip unknown byte (aggressive cleaning)
            }
        }

        Files.write(file, buffer.toByteArray());
    }
}
