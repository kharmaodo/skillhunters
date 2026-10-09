package com.skillhunters.documents;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import com.skillhunters.shared.ApiProblem.Rejected;

/** Bounded envelope checks only. No Office/PDF/XML parser and no content rendering. */
@Component
public class ReceptionValidator {
    public static final long MAX_BYTES = 15L * 1024 * 1024;
    public static final String VERSION = "reception-v1";
    private static final Map<String, String> MIME = Map.of(
        "pdf", "application/pdf", "doc", "application/msword",
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "md", "text/markdown");

    public record Received(Path path, String name, String mediaType, long size, String sha256) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }

    public Received receive(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 200 || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            throw bad("INVALID_FILE_NAME");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expected = MIME.get(extension);
        if (expected == null) throw bad("UNSUPPORTED_FORMAT");
        String declared = file.getContentType();
        if (declared != null) declared = declared.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (declared != null && !declared.equals("application/octet-stream") && !declared.equals(expected)
                && !(extension.equals("md") && declared.equals("text/plain"))) throw bad("MIME_MISMATCH");
        if (file.isEmpty()) throw bad("EMPTY_FILE");
        if (file.getSize() > MAX_BYTES) throw new Rejected(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        Path temp = Files.createTempFile("sh-quarantine-", ".upload");
        try {
            long size = 0;
            MessageDigest digest = sha();
            try (var in = file.getInputStream(); var out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) {
                    size += n;
                    if (size > MAX_BYTES) throw new Rejected(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
            if (size == 0) throw bad("EMPTY_FILE");
            validate(temp, extension);
            return new Received(temp, name, expected, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temp); throw failure;
        }
    }

    private void validate(Path file, String extension) throws IOException {
        byte[] prefix;
        try (var in = Files.newInputStream(file)) { prefix = in.readNBytes(512); }
        switch (extension) {
            case "pdf" -> {
                String start = new String(prefix, StandardCharsets.ISO_8859_1);
                if (!start.matches("(?s)^%PDF-(1\\.[0-7]|2\\.0)[\\r\\n ].*")) throw bad("SIGNATURE_MISMATCH");
                // Encrypted, malformed and over-page-limit documents remain blocked for the isolated worker.
            }
            case "doc" -> {
                byte[] magic = HexFormat.of().parseHex("d0cf11e0a1b11ae1");
                if (prefix.length < 512 || !java.util.Arrays.equals(magic, java.util.Arrays.copyOf(prefix, 8))
                        || prefix[28] != (byte)0xfe || prefix[29] != (byte)0xff) throw bad("SIGNATURE_MISMATCH");
                // CFB is only a container signature, not proof of Word content or macro absence.
            }
            case "docx" -> validateZip(file);
            case "md" -> {
                try {
                    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
                    if (text.isBlank() || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                        throw bad("INVALID_TEXT");
                    }
                } catch (CharacterCodingException invalid) { throw bad("INVALID_TEXT"); }
            }
            default -> throw bad("UNSUPPORTED_FORMAT");
        }
    }

    private void validateZip(Path file) {
        long total = 0;
        var names = new HashSet<String>();
        try (var zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (names.size() >= 1000 || !names.add(name) || name.startsWith("/") || name.contains("\\")
                        || name.contains(":") || java.util.Arrays.asList(name.split("/")).contains("..")
                        || lower.endsWith(".bin") || lower.contains("vbaproject") || lower.startsWith("word/embeddings/")) {
                    throw bad("UNSAFE_ARCHIVE");
                }
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getCompressedSize() < 0 || entry.getSize() > 30L * 1024 * 1024
                        || entry.getSize() > Math.max(1, entry.getCompressedSize()) * 100) throw bad("ARCHIVE_LIMIT");
                long actual = 0;
                try (var in = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        actual += n; total += n;
                        if (actual > 30L * 1024 * 1024 || total > 60L * 1024 * 1024
                                || actual > Math.max(1, entry.getCompressedSize()) * 100) throw bad("ARCHIVE_LIMIT");
                    }
                }
                if (actual != entry.getSize()) throw bad("UNSAFE_ARCHIVE");
            }
            if (!names.contains("[Content_Types].xml") || !names.contains("word/document.xml") || !names.contains("_rels/.rels")) {
                throw bad("SIGNATURE_MISMATCH");
            }
        } catch (IOException | IllegalArgumentException invalid) { throw bad("UNSAFE_ARCHIVE"); }
    }

    public static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Rejected bad(String code) { return new Rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, code); }
}
