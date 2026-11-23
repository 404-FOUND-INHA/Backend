package com.__found.smart_library.domain.observation.service;

/**
 * 파서가 OCR 청구기호 라벨에서 뽑아낸 구성요소. 분류기호·도서기호는 필수이고 복본기호는 선택이다.
 * 값은 DB 표기와 같은 형태로 정규화되어 있어야 한다(정규화는 파서의 책임이며 매처는 문자열을 그대로 비교한다).
 * 복본기호가 비어 있으면 null 로 통일하며, 이는 "복본기호 없음"이지 "c.1"이 아니다.
 */
public record ParsedCallNumber(String classificationNo, String bookSymbol, String copyNumber) {

    public ParsedCallNumber {
        if (classificationNo == null || classificationNo.isBlank()) {
            throw new IllegalArgumentException("분류기호는 필수입니다.");
        }
        if (bookSymbol == null || bookSymbol.isBlank()) {
            throw new IllegalArgumentException("도서기호는 필수입니다.");
        }
        if (copyNumber != null && copyNumber.isBlank()) {
            copyNumber = null;
        }
    }
}
