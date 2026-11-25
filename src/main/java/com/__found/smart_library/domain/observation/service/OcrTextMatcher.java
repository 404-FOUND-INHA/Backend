package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.repository.BookMatchInfo;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OcrTextMatcher {
    private static final Pattern COPY = Pattern.compile("(?U)\\bc\\s*\\.\\s*([0-9]+)\\b", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;
    private final String sql;

    public OcrTextMatcher(JdbcTemplate jdbc) throws IOException {
        this.jdbc = jdbc;
        this.sql = new ClassPathResource("sql/ocr-text-match.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    private record Hit(BookMatchInfo book, String callNumber, BigDecimal score) {}

    public MatchResult match(String text, String partialCopyMark) {
        String normalized = normalize(text);
        String compact = normalized.replace(" ", "");
        if (compact.isEmpty()) return MatchResult.noMatch(List.of());
        StringBuilder ordered = new StringBuilder("%");
        compact.codePoints().forEach(cp -> ordered.append(escape(Character.toString(cp))).append('%'));
        String[] probes = probes(compact);
        List<Hit> hits = jdbc.query(sql, ps -> {
            ps.setString(1, compact);
            ps.setString(2, ordered.toString());
            ps.setArray(3, ps.getConnection().createArrayOf("text", probes));
        }, (r, i) -> new Hit(new BookMatchInfo(r.getLong("book_id"), r.getString("registration_no"),
                r.getString("title"), r.getString("classification_no"), r.getString("book_symbol"),
                r.getString("copy_number"), r.getObject("expected_shelf_id", Long.class),
                LifecycleStatus.valueOf(r.getString("lifecycle_status"))),
                r.getString("call_number_raw"), r.getBigDecimal("score")));
        if (hits.isEmpty()) return MatchResult.noMatch(List.of());
        Hit first = hits.get(0);
        if (hits.size() > 1 && !COPY.matcher(normalized).find() && hits.stream().allMatch(hit ->
                hit.callNumber().equals(first.callNumber()) && normalize(hit.book().title()).equals(normalize(first.book().title())))) {
            List<Hit> preferred = hits.stream().filter(hit -> prefers(hit.book().copyNumber(), partialCopyMark)).toList();
            if (!preferred.isEmpty()) hits = preferred;
        }
        Hit selected = hits.get(0);
        return new MatchResult(MatchResult.Outcome.MATCHED, selected.book(), selected.score(), List.of());
    }

    private static boolean prefers(String copy, String suffix) {
        if (suffix == null) return copy == null || copy.isBlank();
        var m = COPY.matcher(copy == null ? "" : copy.strip());
        return m.matches() && suffix.equals("." + m.group(1));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String[] probes(String compact) {
        int[] units = Normalizer.normalize(compact, Normalizer.Form.NFD).codePoints().toArray();
        int width = Math.min(3, units.length);
        List<String> grams = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i <= units.length - width; i++) {
            String gram = escape(new String(units, i, width));
            grams.add(gram);
            patterns.add("%" + gram + "%");
        }
        int stride = Math.max(width, grams.size() / 2);
        for (int i = 0; i + stride < grams.size(); i++) {
            patterns.add("%" + grams.get(i) + "%" + grams.get(i + stride) + "%");
        }
        return patterns.toArray(String[]::new);
    }
}
