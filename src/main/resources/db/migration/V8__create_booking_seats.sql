create table booking_seats (
    booking_id    uuid not null references bookings (id) on delete cascade,
    event_seat_id uuid not null references event_seats (id),
    primary key (booking_id, event_seat_id)
);
alter table tickets add column event_seat_id uuid references event_seats (id);
