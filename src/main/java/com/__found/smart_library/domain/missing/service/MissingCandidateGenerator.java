package com.__found.smart_library.domain.missing.service;

import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.book.repository.ExpectedBook;
import com.__found.smart_library.domain.missing.entity.MissingCandidate;
import com.__found.smart_library.domain.missing.repository.MissingCandidateRepository;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.repository.ScanSessionShelfRepository;
import com.__found.smart_library.domain.scan.repository.ShelfCheck;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회차 확정 시 소재불명 후보를 만든다(확정과 같은 트랜잭션에서 호출).
 *
 * <p>후보 = 점검된 서가가 정상 배치 서가인 도서 − 이 회차에서 발견된 도서.
 * <ul>
 *   <li>발견: 정상·오배치로 위치가 확인된 도서. 다른 서가에서 발견된 도서도 발견으로 본다(소재불명이 아니다).</li>
 *   <li>제외: 제적 도서, 대출 중 도서, 분실 처리된 도서, 점검하지 않은(결과 미수신) 서가의 도서.</li>
 *   <li>연속 미발견 횟수: 그 서가를 직전에 점검한 확정 회차에서도 후보였고 그 후보가 찾음·해결로 종결되지 않았다면
 *       직전 횟수 + 1, 아니면 1. 직전 점검에서 발견·제외되었거나 점검 이력이 없으면 1 이다.</li>
 * </ul>
 * 회차 확정은 한 번만 성공하므로(회차 락) 같은 회차·도서의 후보가 중복 생성되지 않는다. 유일 제약이 한 번 더 막는다.
 */
@Component
@RequiredArgsConstructor
public class MissingCandidateGenerator {

    private static final int CHUNK = 1000;

    private final ScanSessionShelfRepository sessionShelfRepository;
    private final BookRepository bookRepository;
    private final MissingCandidateRepository candidateRepository;

    /**
     * @param session  finalizedAt 이 이미 기록된 회차
     * @param foundBookIds 이 회차에서 위치가 확인된 도서
     * @return 생성한 후보 수
     */
    public long generate(ScanSession session, Set<Long> foundBookIds, LocalDateTime createdAt) {
        List<Long> checkedShelfIds = sessionShelfRepository.findCheckedShelfIds(session.getId());
        if (checkedShelfIds.isEmpty()) {
            return 0;
        }
        List<ExpectedBook> missing = new ArrayList<>();
        for (List<Long> chunk : chunks(checkedShelfIds)) {
            for (ExpectedBook b : bookRepository.findExpectedOnShelves(chunk)) {
                if (!foundBookIds.contains(b.bookId())) {
                    missing.add(b);
                }
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }

        Map<Long, Long> previousSessionByShelf = previousCheckedSessions(session, missing);
        Map<Long, MissingCandidate> previousByBook = previousCandidates(missing, previousSessionByShelf);

        List<MissingCandidate> candidates = new ArrayList<>(missing.size());
        for (ExpectedBook b : missing) {
            MissingCandidate prev = previousByBook.get(b.bookId());
            int count = prev != null && !prev.getStatus().breaksStreak() ? prev.getConsecutiveMissingCount() + 1 : 1;
            candidates.add(MissingCandidate.create(session.getId(), b.bookId(), b.shelfId(), count, createdAt));
        }
        candidateRepository.saveAll(candidates);
        return candidates.size();
    }

    /** 서가 -> 그 서가를 직전에 점검한 확정 회차. */
    private Map<Long, Long> previousCheckedSessions(ScanSession session, List<ExpectedBook> missing) {
        Set<Long> shelfIds = new HashSet<>();
        missing.forEach(b -> shelfIds.add(b.shelfId()));
        Map<Long, ShelfCheck> latest = new HashMap<>();
        for (List<Long> chunk : chunks(shelfIds)) {
            for (ShelfCheck c : sessionShelfRepository.findCompletedChecks(
                    chunk, session.getId(), session.getFinalizedAt())) {
                latest.merge(c.shelfId(), c, (a, b) -> b.finalizedAt().isAfter(a.finalizedAt()) ? b : a);
            }
        }
        Map<Long, Long> result = new HashMap<>();
        latest.forEach((shelfId, c) -> result.put(shelfId, c.sessionId()));
        return result;
    }

    /** 도서 -> 직전 점검 회차의 후보(없으면 키 없음). */
    private Map<Long, MissingCandidate> previousCandidates(List<ExpectedBook> missing,
                                                           Map<Long, Long> previousSessionByShelf) {
        Map<Long, Long> shelfByBook = new HashMap<>();
        for (ExpectedBook b : missing) {
            if (previousSessionByShelf.containsKey(b.shelfId())) {
                shelfByBook.put(b.bookId(), b.shelfId());
            }
        }
        Set<Long> sessionIds = new HashSet<>(previousSessionByShelf.values());
        Map<Long, MissingCandidate> result = new HashMap<>();
        for (List<Long> chunk : chunks(shelfByBook.keySet())) {
            for (MissingCandidate m : candidateRepository.findByBooksAndSessions(chunk, sessionIds)) {
                Long expectedPrevSession = previousSessionByShelf.get(shelfByBook.get(m.getBookId()));
                if (m.getScanSessionId().equals(expectedPrevSession)) {
                    result.put(m.getBookId(), m);
                }
            }
        }
        return result;
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> list = new ArrayList<>(ids);
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK) {
            out.add(list.subList(i, Math.min(i + CHUNK, list.size())));
        }
        return out;
    }
}
