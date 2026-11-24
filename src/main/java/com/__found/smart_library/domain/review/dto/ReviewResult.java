package com.__found.smart_library.domain.review.dto;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.review.entity.Review;
import com.__found.smart_library.domain.review.entity.ReviewAction;
import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;
import com.__found.smart_library.domain.review.repository.ReviewItemRow;
import java.time.LocalDateTime;

/** 사서 검토 결과. finalBook 은 도서를 확정한 경우에만 있다. */
public record ReviewResult(
        Long reviewId,
        ReviewAction action,
        ReviewFinalStatus finalStatus,
        BookBrief finalBook,
        String comment,
        Long reviewerId,
        String reviewerName,
        LocalDateTime reviewedAt
) {

    public static ReviewResult of(Review review, Book finalBook, String reviewerName) {
        return new ReviewResult(review.getId(), review.getReviewAction(), review.getFinalStatus(),
                finalBook == null ? null : BookBrief.from(finalBook), review.getComment(), review.getReviewerId(),
                reviewerName, review.getReviewedAt());
    }

    /** 검토 전이면 null. */
    public static ReviewResult from(ReviewItemRow r) {
        if (r.reviewId() == null) {
            return null;
        }
        BookBrief finalBook = r.finalBookId() == null ? null
                : new BookBrief(r.finalBookId(), r.finalRegistrationNo(), r.finalTitle(), r.finalCallNumber());
        return new ReviewResult(r.reviewId(), r.reviewAction(), r.finalStatus(), finalBook, r.comment(),
                r.reviewerId(), r.reviewerName(), r.reviewedAt());
    }
}
