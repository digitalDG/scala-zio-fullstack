CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT        NOT NULL,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT users_username_key UNIQUE (username),
    CONSTRAINT users_email_key    UNIQUE (email)
);

CREATE TABLE todos (
    id          BIGSERIAL PRIMARY KEY,
    owner_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title       TEXT        NOT NULL,
    description TEXT,
    completed   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 0 = low, 1 = medium, 2 = high; stored as a number so it sorts by importance.
    priority    SMALLINT    NOT NULL CHECK (priority BETWEEN 0 AND 2),
    due_date    DATE,
    tags        TEXT[]      NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX todos_owner_created_idx ON todos (owner_id, created_at DESC);
CREATE INDEX todos_tags_idx ON todos USING GIN (tags);
