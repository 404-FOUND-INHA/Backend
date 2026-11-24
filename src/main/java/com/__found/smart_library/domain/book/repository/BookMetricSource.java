package com.__found.smart_library.domain.book.repository;

import java.time.LocalDate;

/** 평가 지표 계산에 필요한 도서 정보. */
public record BookMetricSource(Long bookId, int totalLoanCount, LocalDate lastLoanDate, String classificationNo,
                               String bookSymbol) {
}
