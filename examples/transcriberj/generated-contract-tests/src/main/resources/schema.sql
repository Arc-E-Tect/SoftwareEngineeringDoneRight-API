-- The catalogue. The ISBN is the natural key: a second book with the same ISBN is refused.
-- The lengths the contract sets are the HTTP adapter's to enforce, not the table's.
CREATE TABLE IF NOT EXISTS book (
    id       UUID PRIMARY KEY,
    isbn     VARCHAR NOT NULL UNIQUE,
    title    VARCHAR NOT NULL,
    subtitle VARCHAR,
    pages    INTEGER NOT NULL,
    format   VARCHAR NOT NULL
);
