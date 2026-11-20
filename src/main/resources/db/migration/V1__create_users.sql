create table users (
    id            uuid primary key,
    email         varchar(254) not null unique check (email = lower(email)),
    password_hash varchar(100) not null,
    name          varchar(100) not null,
    created_at    timestamptz  not null
);
create table user_roles (
    user_id uuid        not null references users (id) on delete cascade,
    role    varchar(20) not null check (role in ('CUSTOMER', 'ORGANIZER', 'ADMIN')),
    primary key (user_id, role)
);
