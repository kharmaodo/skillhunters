package com.skillhunters.documents;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Bounded, local rules over already antivirus-gated text. No model, tools or I/O. */
@Component
public class StructuredExtractor {
    public static final String RULES = "sh09-rules-1";
    public static final String SCHEMA = "sh09-proposals-1";
    public enum Field { FULL_NAME, TITLE, AVAILABILITY, SKILL, DECLARED_LEVEL, EXPERIENCE, EDUCATION, LANGUAGE, CERTIFICATION_DECLARATION }
    public enum State { UNKNOWN, PROPOSED, AMBIGUOUS }
    public enum Precision { UNKNOWN, TEXT, DECLARED_LEVEL }
    public record Evidence(int blockIndex, String source, int start, int end, String quote) {}
    public record Claim(Field field, int item, String raw, String normalized, State status, Precision precision,
                        Double confidence, List<Evidence> evidence, List<String> warnings) {}
    public record Proposal(String schemaVersion, String rulesVersion, String modelVersion, String promptVersion,
                           String status, List<Claim> claims, List<String> warnings) {}
    private record Line(String text, Evidence evidence) {}
    private static final int MAX_CLAIMS = 500;
    private static final Pattern LEVEL = Pattern.compile("^(.{1,160}?)\\s*(?:[:|=]\\s*|\\s+)([123](?:[.,]5)?)(?:\\s*/\\s*3)?$");
    private static final Pattern CELL = Pattern.compile("^(.*?/tr\\[\\d+\\])/tc\\[\\d+\\]/p\\[\\d+\\]$");
    private static final Set<String> UNKNOWN = Set.of("inconnu", "inconnue", "unknown", "non renseigne", "non renseignee", "n/a", "?");
    private static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
            .replaceAll("^[#*\\s]+|[:*\\s]+$", "").strip();
    }
    private static Field label(String text) {
        return switch (key(text)) {
            case "nom", "nom complet", "full name", "name" -> Field.FULL_NAME;
            case "titre", "title", "job title" -> Field.TITLE;
            case "disponibilite", "availability" -> Field.AVAILABILITY;
            case "competence", "competences", "competences techniques", "skill", "skills", "technical skills" -> Field.SKILL;
            case "experience", "experiences", "experiences professionnelles", "professional experience", "work experience" -> Field.EXPERIENCE;
            case "formation", "formations", "education" -> Field.EDUCATION;
            case "langue", "langues", "language", "languages" -> Field.LANGUAGE;
            case "certification", "certifications" -> Field.CERTIFICATION_DECLARATION;
            default -> null;
        };
    }
    private static boolean excluded(String text) {
        return key(text).matches("(?:age|photo|genre|gender|sexe|nationalite|nationality|religion|sante|health|situation familiale|marital status|loisirs|hobbies|sport(?:s)?|vie associative|activites associatives|interests)(?:\\s*:.*)?");
    }
    public Proposal extract(List<DocumentAnalyzer.Block> blocks) {
        if (blocks == null || blocks.size() > 5000 || blocks.stream().mapToLong(b -> b.text().length()).sum() > 500000)
            throw new IllegalArgumentException("EXTRACTION_INPUT_LIMIT");
        List<Claim> claims = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        warnings.add("RULE_BASED_LIMITED_COVERAGE");
        Field section = null;
        boolean excludedSection = false;
        Map<Field, Integer> counts = new EnumMap<>(Field.class);
        for (int bi = 0; bi < blocks.size(); bi++) {
            var block = blocks.get(bi);
            // A template skill row is interpreted only inside an explicitly identified skill section.
            var cell = CELL.matcher(block.source());
            if (section == Field.SKILL && !excludedSection && cell.matches()) {
                var row = new ArrayList<Line>();
                int next = bi;
                while (next < blocks.size() && row.size() < 4) {
                    var candidate = blocks.get(next);
                    var other = CELL.matcher(candidate.source());
                    if (!other.matches() || !other.group(1).equals(cell.group(1))) break;
                    row.add(new Line(candidate.text(), new Evidence(next, candidate.source(), 0, candidate.text().length(), candidate.text())));
                    next++;
                }
                int nameIndex = row.size() == 3 ? 1 : 0;
                if ((row.size() == 2 || row.size() == 3) && label(row.get(nameIndex).text()) == null && !excluded(row.get(nameIndex).text())
                        && row.stream().allMatch(line -> line.text().length() <= 2048)
                        && row.get(nameIndex).text().length() <= 160 && row.getLast().text().strip().matches("[123](?:[.,]5)?")) {
                    int item = counts.merge(Field.SKILL, 1, Integer::sum);
                    skill(claims, item, row.get(nameIndex).text().strip(), row.getLast().text().strip(), row.stream().map(Line::evidence).toList());
                    bi = next - 1;
                    if (claims.size() >= MAX_CLAIMS) { warnings.add("CLAIM_LIMIT"); break; }
                    continue;
                }
            }
            var lines = Pattern.compile("[^\\r\\n]+").matcher(block.text());
            while (lines.find()) {
                String text = lines.group().strip();
                if (text.isEmpty()) continue;
                if (text.length() > 2048) { warnings.add("LONG_LINE_SKIPPED"); section = null; continue; }
                Evidence evidence = new Evidence(bi, block.source(), lines.start(), lines.end(), lines.group());
                if (excluded(text)) { section = null; excludedSection = true; continue; }
                Field heading = label(text);
                if (heading != null) { section = heading; excludedSection = false; continue; }
                int colon = text.indexOf(':');
                Field field = colon > 0 ? label(text.substring(0, colon)) : null;
                String value = field == null ? text.replaceFirst("^[-*]\\s+", "") : text.substring(colon + 1).strip();
                if (field != null) { section = field; excludedSection = false; }
                else {
                    // Unknown Markdown headings and labels end the current section: no inheritance into hobbies etc.
                    if (text.startsWith("#") || (colon > 0 && !(section == Field.SKILL && LEVEL.matcher(value).matches()))) { section = null; continue; }
                    if (excludedSection || section == null) continue;
                    field = section;
                }
                if (value.isBlank() || UNKNOWN.contains(key(value))) continue;
                int item = counts.merge(field, 1, Integer::sum);
                if (field == Field.SKILL) {
                    var level = LEVEL.matcher(value);
                    if (level.matches()) skill(claims, item, level.group(1).strip(), level.group(2), List.of(evidence));
                    else skill(claims, item, value, null, List.of(evidence));
                } else {
                    var flags = new ArrayList<String>();
                    if (field == Field.EXPERIENCE) flags.add("PERIOD_AND_KIND_UNRESOLVED");
                    if (field == Field.CERTIFICATION_DECLARATION) flags.add("ATTAINMENT_UNVERIFIED");
                    if (field == Field.AVAILABILITY) flags.add("AVAILABILITY_DATE_UNRESOLVED");
                    claims.add(claim(field, item, value, value, State.PROPOSED, Precision.TEXT, List.of(evidence), flags));
                    // Identity/title/availability are not multiline sections.
                    if (Set.of(Field.FULL_NAME, Field.TITLE, Field.AVAILABILITY).contains(field)) section = null;
                }
                if (claims.size() >= MAX_CLAIMS) { warnings.add("CLAIM_LIMIT"); break; }
            }
            if (claims.size() >= MAX_CLAIMS) break;
        }
        for (Field field : List.of(Field.FULL_NAME, Field.TITLE, Field.AVAILABILITY, Field.SKILL,
                                  Field.EXPERIENCE, Field.EDUCATION, Field.LANGUAGE, Field.CERTIFICATION_DECLARATION)) {
            if (claims.stream().noneMatch(c -> c.field() == field))
                claims.add(claim(field, 0, null, null, State.UNKNOWN, Precision.UNKNOWN, List.of(), List.of()));
        }
        // Conflicting singleton declarations are kept with all citations, never silently overwritten.
        for (Field field : List.of(Field.FULL_NAME, Field.TITLE, Field.AVAILABILITY)) {
            if (claims.stream().filter(c -> c.field() == field).map(Claim::normalized).distinct().count() > 1) {
                claims.replaceAll(c -> c.field() == field ? claim(c.field(), c.item(), c.raw(), c.normalized(), State.AMBIGUOUS,
                    c.precision(), c.evidence(), List.of("CONFLICTING_DECLARATIONS")) : c);
            }
        }
        var proposal = new Proposal(SCHEMA, RULES, "none", "none", "NEEDS_REVIEW", List.copyOf(claims), List.copyOf(warnings));
        verify(proposal, blocks);
        return proposal;
    }
    private static void skill(List<Claim> claims, int item, String name, String level, List<Evidence> evidence) {
        boolean group = name.matches(".*(?:/|,|;|\\s&\\s|\\set\\s|\\sand\\s).*" );
        var flags = group ? List.of("GROUP_ATTRIBUTION_AMBIGUOUS") : List.<String>of();
        claims.add(claim(Field.SKILL, item, name, name, group ? State.AMBIGUOUS : State.PROPOSED, Precision.TEXT, evidence, flags));
        if (level == null) claims.add(claim(Field.DECLARED_LEVEL, item, null, null, State.UNKNOWN, Precision.UNKNOWN, List.of(), List.of()));
        else {
            double number = Double.parseDouble(level.replace(',', '.'));
            boolean valid = number >= 1 && number <= 3;
            claims.add(claim(Field.DECLARED_LEVEL, item, level, valid ? level.replace(',', '.') : null,
                valid && !group ? State.PROPOSED : State.AMBIGUOUS, Precision.DECLARED_LEVEL, evidence,
                !valid ? List.of("LEVEL_OUT_OF_RANGE") : flags));
        }
    }
    private static Claim claim(Field field, int item, String raw, String normalized, State state, Precision precision,
                               List<Evidence> evidence, List<String> warnings) {
        return new Claim(field, item, raw, normalized, state, precision, null, List.copyOf(evidence), List.copyOf(warnings));
    }
    /** Every proposed value has exact UTF-16 offsets into its immutable source snapshot. */
    public static void verify(Proposal proposal, List<DocumentAnalyzer.Block> blocks) {
        if (!SCHEMA.equals(proposal.schemaVersion()) || !RULES.equals(proposal.rulesVersion())
                || !"NEEDS_REVIEW".equals(proposal.status()) || proposal.claims().size() > MAX_CLAIMS + 9)
            throw new IllegalArgumentException("INVALID_PROPOSAL");
        for (var c : proposal.claims()) {
            if (c.status() == State.UNKNOWN) {
                if (c.raw() != null || c.normalized() != null || !c.evidence().isEmpty()) throw new IllegalArgumentException("INVALID_UNKNOWN");
                continue;
            }
            if (c.raw() == null || c.evidence().isEmpty()) throw new IllegalArgumentException("MISSING_EVIDENCE");
            for (var e : c.evidence()) {
                if (e.blockIndex() < 0 || e.blockIndex() >= blocks.size()) throw new IllegalArgumentException("INVALID_EVIDENCE");
                var b = blocks.get(e.blockIndex());
                if (!b.source().equals(e.source()) || e.start() < 0 || e.end() > b.text().length() || e.end() <= e.start()
                        || !b.text().substring(e.start(), e.end()).equals(e.quote())) throw new IllegalArgumentException("INVALID_EVIDENCE");
            }
            if (c.evidence().stream().noneMatch(e -> e.quote().contains(c.raw()))) throw new IllegalArgumentException("UNSOURCED_VALUE");
        }
    }
}
