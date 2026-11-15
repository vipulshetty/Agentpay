-- The gateway must not take a merchant's category from the agent that is paying it: an agent can
-- type "groceries" for any merchant. Categories are resolved here, from a directory the operator
-- controls, and the agent-supplied value is kept only as a hint on the payment record.
create table merchants (
    merchant_id text primary key,
    category    text not null,
    created_at  timestamptz not null default now()
);
