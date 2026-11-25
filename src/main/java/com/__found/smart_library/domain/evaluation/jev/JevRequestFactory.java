package com.__found.smart_library.domain.evaluation.jev;

import com.__found.smart_library.domain.book.entity.Book;
import java.time.Clock;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 도서 1권의 JEV 요청을 만든다. state 에는 서지 정보만 넣는다(모델은 책 본문을 보지 못한다).
 * 내용 유효성 판단에 필요하므로 평가 기준 연도(evaluationYear)를 함께 보낸다. 값이 없는 정보는 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JevRequestFactory {

    private final JevProperties properties;
    private final Clock clock;

    public JevRequest create(Book book) {
        Map<String, Object> state = new LinkedHashMap<>();
        put(state, "title", book.getTitle());
        put(state, "author", book.getAuthor());
        put(state, "publisher", book.getPublisher());
        put(state, "publishedYear", book.getPublishedYear());
        put(state, "classificationNo", book.getClassificationNo());
        put(state, "isbn", book.getIsbn());
        state.put("evaluationYear", Year.now(clock).getValue());
        return new JevRequest(properties.model(), state, JevQuestions.build());
    }

    private static void put(Map<String, Object> state, String key, Object value) {
        if (value instanceof String text ? !text.isBlank() : value != null) {
            state.put(key, value);
        }
    }
}
