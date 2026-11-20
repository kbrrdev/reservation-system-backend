create table bookings (
    id               uuid primary key,
    user_id          uuid        not null references users (id),
    event_id         uuid        not null references events (id),
    type             varchar(10) not null check (type in ('PURCHASE', 'COMP')),
    status           varchar(20) not null check (status in ('PENDING_PAYMENT','CONFIRMED','EXPIRED','CANCELLED')),
    total_minor      bigint      not null check (total_minor >= 0),
    currency         char(3)     not null,
    expires_at       timestamptz not null,
    payment_provider varchar(30),
    created_at       timestamptz not null,
    cancelled_at     timestamptz,
    cancelled_by     uuid references users (id),
    version          bigint      not null default 0
);
create unique index bookings_one_pending_purchase on bookings (user_id, event_id)
    where status = 'PENDING_PAYMENT' and type = 'PURCHASE';
create index bookings_pending_expiry on bookings (expires_at) where status = 'PENDING_PAYMENT';
create index bookings_user_created on bookings (user_id, created_at desc);
create table booking_items (
    id               uuid primary key,
    booking_id       uuid   not null references bookings (id) on delete cascade,
    ticket_type_id   uuid   not null references ticket_types (id),
    quantity         int    not null check (quantity >= 1),
    unit_price_minor bigint not null check (unit_price_minor >= 0),
    unique (booking_id, ticket_type_id)
);
