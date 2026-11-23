package com.__found.smart_library.domain.observation.service;

/**
 * 파서가 OCR 청구기호 라벨에서 뽑아낸 구성요소. 분류기호·도서기호는 필수이고 복본기호는 선택이다.
 * 값은 DB 표기와 같은 형태로 정규화되어 있어야 한다(정규화는 파서의 책임이며 매처는 문자열을 그대로 비교한다).
 * 복본기호가 비어 있으면 null 로 통일하며, 이는 "복본기호 없음"이지 "c.1"이 아니다.
 *
 * <p>각 값은 저장 컬럼(book_observation.detected_*)의 길이를 넘을 수 없다. 이를 넘거나 필수값이 비어 있는 결과를 만드는 것은
 * 라벨을 읽지 못한 경우(파서가 빈 결과를 돌려주는 OCR 인식 실패)와 달리 <b>파서 계약 위반</b>이므로 예외로 알리고,
 * 값을 잘라 저장하거나 OCR_FAILED 로 숨기지 않는다. 해석할 수 없는 라벨은 파서가 {@code Optional.empty()} 로 돌려줘야 한다.
 */
public record ParsedCallNumber(String classificationNo, String bookSymbol, String copyNumber) {

    /** book_observation.detected_classification_no, detected_book_symbol, detected_copy_number 컬럼 길이. */
    public static final int MAX_CLASSIFICATION_NO_LENGTH = 50;
    public static final int MAX_BOOK_SYMBOL_LENGTH = 50;
    public static final int MAX_COPY_NUMBER_LENGTH = 30;

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
        requireMaxLength("분류기호", classificationNo, MAX_CLASSIFICATION_NO_LENGTH);
        requireMaxLength("도서기호", bookSymbol, MAX_BOOK_SYMBOL_LENGTH);
        requireMaxLength("복본기호", copyNumber, MAX_COPY_NUMBER_LENGTH);
    }

    /** DB 의 VARCHAR(n) 은 문자(코드포인트) 수 기준이다. 값 자체는 메시지에 넣지 않는다. */
    private static void requireMaxLength(String name, String value, int max) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(name + "는 최대 " + max + "자입니다(파서 결과가 저장 컬럼 길이를 초과).");
        }
    }
}
