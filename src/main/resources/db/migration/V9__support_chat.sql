-- Support chat: one running conversation per customer or rider, answered by admins.
CREATE TABLE support_conversations (
    id               uuid          PRIMARY KEY,
    version          bigint,
    user_id          uuid          NOT NULL,   -- the customer's or rider's profile id
    role             varchar(16)   NOT NULL,
    name             varchar(255),
    phone            varchar(255),
    status           varchar(16)   NOT NULL,
    awaiting_reply   boolean       NOT NULL,   -- the last word is the user's
    awaiting_since   timestamptz,              -- when the oldest unanswered user message arrived
    user_unread      integer       NOT NULL,   -- staff replies the user has not opened yet
    last_message_at  timestamptz   NOT NULL,
    last_preview     varchar(160),
    created_at       timestamptz   NOT NULL,
    CONSTRAINT uq_support_user UNIQUE (user_id)
);
CREATE INDEX idx_support_inbox ON support_conversations (awaiting_reply, awaiting_since);
CREATE INDEX idx_support_recent ON support_conversations (status, last_message_at DESC);

CREATE TABLE support_messages (
    id               uuid          PRIMARY KEY,
    conversation_id  uuid          NOT NULL,
    sender           varchar(8)    NOT NULL,   -- USER or STAFF
    staff_id         uuid,
    body             varchar(2000) NOT NULL,
    trip_id          uuid,                     -- the trip the user says this is about
    created_at       timestamptz   NOT NULL
);
CREATE INDEX idx_support_messages ON support_messages (conversation_id, created_at);
