package com.__found.smart_library.domain.evaluation.jev;

import java.util.List;

/**
 * 회차 JEV 평가 결과 요약.
 *
 * @param requested 이번에 평가 대상이 된 도서 수(이미 평가된 도서 제외 전 전체)
 * @param succeeded 이번 호출로 새로 저장한 도서 수
 * @param skipped   이미 JEV 결과가 있어 호출하지 않은(또는 동시에 저장되어 건너뛴) 도서 수
 * @param failed    호출이나 응답 검증에 실패해 저장하지 못한 도서. 다시 요청하면 이 도서만 다시 평가한다.
 */
public record JevEvaluationResponse(int requested, int succeeded, int skipped, List<Failure> failed) {

    public record Failure(Long bookId, String reason) {
    }
}
