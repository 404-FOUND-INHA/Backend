WITH q AS (SELECT ?::text AS input, ?::text AS ordered, ?::text[] AS probes),
catalog AS (
    SELECT b.*, replace(lower(regexp_replace(normalize(concat_ws(' ', title, call_number_raw,
               nullif(copy_number, '')), NFC), '[[:space:]]+', ' ', 'g')), ' ', '') AS compact
    FROM book b
),
scored AS (
    SELECT book_id, registration_no, title, classification_no, book_symbol, call_number_raw,
           copy_number, expected_shelf_id, lifecycle_status,
           CASE
               WHEN strpos(compact, q.input) > 0 THEN 1.0
               WHEN compact LIKE q.ordered THEN (
                   WITH RECURSIVE alignment(start_pos, pos, matched) AS (
                       SELECT p, p, 1 FROM generate_series(1, char_length(compact)) AS starts(p)
                       WHERE substr(compact, p, 1) = left(q.input, 1)
                       UNION ALL
                       SELECT start_pos, pos + step, matched + 1 FROM alignment
                       CROSS JOIN LATERAL (
                           SELECT strpos(substr(compact, pos + 1), substr(q.input, matched + 1, 1)) AS step
                       ) AS advance
                       WHERE matched < char_length(q.input) AND step > 0
                   )
                   SELECT 0.90 + 0.10 / (1 + min(pos - start_pos + 1 - matched))
                   FROM alignment WHERE matched = char_length(q.input)
               )
               ELSE 0.9 * (
                   SELECT avg((jamos LIKE probe)::int) FROM unnest(q.probes) AS patterns(probe)
               )
           END AS score
    FROM catalog CROSS JOIN q
    CROSS JOIN LATERAL (SELECT normalize(compact, NFD) AS jamos OFFSET 0) AS target
)
SELECT * FROM (SELECT * FROM scored ORDER BY score DESC FETCH FIRST 1 ROW WITH TIES) winners
WHERE score > 0
ORDER BY book_id
