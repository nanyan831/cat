ALTER TABLE messages ADD COLUMN sequence_number BIGINT;

WITH ordered AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY conversation_id
        ORDER BY created_at, id
    ) AS assigned_sequence
    FROM messages
)
UPDATE messages
SET sequence_number = ordered.assigned_sequence
FROM ordered
WHERE messages.id = ordered.id;

ALTER TABLE messages ALTER COLUMN sequence_number SET NOT NULL;
ALTER TABLE messages ADD COLUMN reply_to_message_id UUID REFERENCES messages(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX messages_conversation_sequence_unique
    ON messages (conversation_id, sequence_number);
CREATE UNIQUE INDEX messages_reply_once_unique
    ON messages (reply_to_message_id)
    WHERE reply_to_message_id IS NOT NULL;

