package com.__found.smart_library.domain.evaluation.jev;

/** JEV(System One) 호출. 구현은 HTTP 호출을 맡고, 응답 검증은 호출하는 쪽({@link JevResultMapper})이 한다. */
public interface JevClient {

    /**
     * 평가를 요청한다.
     *
     * @throws JevCallException 호출이 끝내 실패했을 때(재시도 후 포함)
     */
    JevResponse evaluate(JevRequest request);
}
