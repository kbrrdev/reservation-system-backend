create table venues (
    id           uuid primary key,
    owner_id     uuid         not null references users (id),
    name         varchar(200) not null,
    address      varchar(300) not null,
    city         varchar(100) not null,
    country_code char(2)      not null check (country_code = upper(country_code)),
    time_zone    varchar(64)  not null,
    version      bigint       not null default 0
);
create index venues_owner on venues (owner_id);
