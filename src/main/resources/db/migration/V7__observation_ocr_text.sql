ALTER TABLE book_observation
    ADD COLUMN ocr_text varchar(2048),
    ADD COLUMN partial_copy_mark varchar(30);
