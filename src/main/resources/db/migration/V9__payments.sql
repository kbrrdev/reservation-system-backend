alter table bookings
    add column payment_reference varchar(100),
    add column checkout_url      varchar(500),
    add column refund_required   boolean not null default false;
create index bookings_refund_required on bookings (created_at) where refund_required;
create table payment_events (
    id                uuid primary key,
    provider          varchar(30)  not null,
    provider_event_id varchar(100) not null,
    type              varchar(100) not null,
    paid              boolean      not null,
    amount_minor      bigint,
    currency          char(3),
    received_at       timestamptz  not null,
    payload           text         not null,
    booking_id        uuid references bookings (id),
    refund_handled    boolean      not null default false,
    unique (provider, provider_event_id)
);
create index payment_events_unmatched on payment_events (received_at)
    where paid and booking_id is null and not refund_handled;
-- A seat can never carry two live tickets (M3 carry-over; partial so a VOID ticket's seat can be sold again in M5/M6).
create unique index tickets_one_live_per_seat on tickets (event_seat_id)
    where event_seat_id is not null and status <> 'VOID';
