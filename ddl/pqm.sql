
create schema if not exists sys;
create schema if not exists sys_partitions;

create table sys.pqm_log (
    pqm_log_id bigserial not null primary key,
    pqm_log_date timestamp with time zone not null default clock_timestamp()
    queue_name text null,
    guid uuid not null,
    event_code text not null,
    payload text,
    error text,
    query_exec_duration_ms integer
);

create table sys_partitions.pqm_log_21010101 (
    like pqm_log including all,
    constraint pqm_log_21010101_timestamp_check check (pqm_log_date >= '21010101' and pqm_log_date < '21010201')
) inherits (pqm_log);


create table sys.pqm_dbcrontab (
    pqm_dbcrontab_id bigserial not null primary key,
    job_name text not null,
    cron_expression text not null,
    queue_name text not null,
    routine_name text not null,
    args jsonb null,
    min_execution_time bigint,
    max_execution_time bigint
);

comment on column sys.pqm_dbcrontab.cron_expression is 'in Quartz format. For example https://www.freeformatter.com/cron-expression-generator-quartz.html';





create or replace function sys.get_partition_name(
	p_table_name varchar,
	p_current_timestamp integer,
	p_interval varchar default 'month'::varchar
) returns varchar
    immutable
    cost 0
    language plpgsql
as
$$
declare
    v_pattern text = 'YYYYMMDD';
begin
    if ('1 '||p_interval)::interval < '1 day'::interval then v_pattern = v_pattern || '_HH24MI'; end if;
    return p_table_name || '_' || to_char(to_timestamp(p_current_timestamp - extract(timezone from now()))::date, v_pattern);
end
$$;

create function sys.get_partition_name(
	p_table_name varchar,
	p_current_datetime timestamp with time zone,
	p_interval varchar default 'month'::varchar
) returns varchar
    immutable
    cost 0
    language plpgsql
as
$$
declare
    v_pattern text = 'YYYYMMDD';
begin
    if ('1 '||p_interval)::interval < '1 day'::interval then v_pattern = v_pattern || '_HH24MI'; end if;
    return p_table_name || '_' || to_char(date_trunc(p_interval, p_current_datetime), v_pattern);
end
$$;

create function sys.partition_exists(
	p_table_name varchar
) returns boolean
    security definer
    language plpgsql
as
$$
begin
    return exists (
        select 1
        from pg_class c
            join pg_namespace nc on nc.oid = c.relnamespace
        where c.relkind = 'r'::char
            and nc.nspname in ('public','partitions','sys','sys_partitions','audit')
            and c.relname = p_table_name::name
    );
end;
$$;

create or replace function sys.create_partitions(
	p_table_name varchar default null::varchar,
	p_current_timestamp integer default null::integer,
	p_interval varchar default 'month'::varchar,
	p_timestamp_column varchar default 'timestamp'::varchar,
	p_namespace varchar default 'partitions'::varchar
) returns void
    security definer
    language plpgsql
as
$fun$
declare

    csr_tables  cursor (key varchar) for
                select distinct
                    c1.relpersistence,
                    n1.nspname as parent_namespace,
                    c1.relname as parent_table,
                    n2.nspname as child_namespace,
                    c2.relname as child_table,
                    regexp_replace(regexp_replace(pg_get_constraintdef(ch.oid,true),$$(?p)(>=[^\d]*)\d+$$, $$\1#min#$$, 'g'),$$(?p)(<[^\d]*)\d+$$, $$\1#max#$$, 'g') as new_check
                from pg_inherits i
                    join pg_class c1 on c1.oid = i.inhparent
                    join pg_namespace n1 on c1.relnamespace = n1.oid
                        and n1.oid in (
                            select oid from pg_namespace
                            where nspowner = sys.current_user_oid()
                                and nspname not like 'old_%'
                                and nspname not like 'new_%'
                            union
                            select oid from pg_namespace
                            where nspname in ('public','partitions','sys','sys_partitions','audit')
                        )
                    left join pg_class c2 on c2.oid = i.inhrelid
                        and c2.oid = (select max(inhrelid) from pg_inherits where inhparent = i.inhparent)
                    left join pg_namespace n2 on n2.oid = c2.relnamespace
                    
                    left join pg_constraint ch on ch.conrelid = c2.oid
                    
                    left join pg_trigger tr on tr.tgrelid = c2.oid
                where (key is null or c1.relname = key)
                order by n1.nspname, c1.relname, c2.relname;
    
    csr_indexes cursor (key varchar) for
                select
                    n.nspname,
                    c1.relname,
                    pg_get_indexdef(i.indexrelid) create_index_sql
                from pg_class c1
                    join pg_namespace n on c1.relnamespace = n.oid
                        and n.oid in (
                            select oid from pg_namespace
                            where nspowner = sys.current_user_oid()
                                and nspname not like 'old_%'
                                and nspname not like 'new_%'
                            union
                            select oid from pg_namespace
                            where nspname in ('public','partitions','sys','sys_partitions','audit')
                        )
                    join pg_index i on i.indrelid = c1.oid
                where c1.relname = key
                    and not i.indisprimary
                order by n.nspname, c1.relname;
    
    l_current_date date = current_date;
    l_partitions_to_create integer = 2;
    l_partition_name text;
    l_primary_key text;
    check_min integer;
    check_max integer;
    
    sql_to_execute text;
    sql_trigger text;

begin
    
    if p_table_name is not null
    and not sys.partition_exists(p_table_name)
    then
        raise exception 'Table % does not exist', p_table_name;
    end if;
    
    if p_current_timestamp is not null then
        l_partitions_to_create := 1;
        l_current_date := date_trunc(p_interval,to_timestamp(p_current_timestamp - extract(timezone from now())::integer))::date;
    end if;
    
    raise notice 'Create partitions (INT) for % (%)', coalesce(p_table_name,'ALL tables'), to_char(l_current_date,'YYYY-MM-DD HH24:MI');
    
    for rec in csr_tables (key := p_table_name)
    loop
        --raise notice 'table_name: %', rec.parent_table;
        --raise notice 'new_check: %', rec.new_check;
        
        for i in 0..l_partitions_to_create-1
        loop
            
            l_partition_name := sys.get_partition_name(rec.parent_table::text, extract(epoch from l_current_date + (i::text||' '||p_interval)::interval)::integer,p_interval);
            check_min := (extract(epoch from l_current_date + (i::text||' '||p_interval)::interval))::int;
            check_max := (extract(epoch from l_current_date + ((i+1)::text||' '||p_interval)::interval))::int;
            
            if sys.partition_exists(l_partition_name) then
                continue;
            end if;
            
            select string_agg(x.column_name::text, ',' order by x.ordinal_position)
            into l_primary_key
            from (
                select kcu.column_name::text, kcu.ordinal_position
                from information_schema.tables t
                    join information_schema.table_constraints tc
                        on tc.table_catalog = t.table_catalog
                        and tc.table_schema = t.table_schema
                        and tc.table_name = t.table_name
                        and tc.constraint_type = 'PRIMARY KEY'
                    join information_schema.key_column_usage kcu
                        on kcu.table_catalog = tc.table_catalog
                        and kcu.table_schema = tc.table_schema
                        and kcu.table_name = tc.table_name
                        and kcu.constraint_name = tc.constraint_name
                where t.table_schema = rec.parent_namespace
                    and t.table_name = rec.parent_table
            ) x;
            
            sql_to_execute := format('CREATE %6$s TABLE IF NOT EXISTS %1$s('
                                        --|| 'like %5$s including all'
                                        || case when l_primary_key > '' then 'CONSTRAINT %2$s_pkey PRIMARY KEY (%4$s),' else '' end
                                        || 'CONSTRAINT %2$s_timestamp_check CHECK %3$s'
                                    || ') INHERITS (%5$s); ALTER TABLE %1$s OWNER TO ' || current_user::text || ';',
                coalesce(rec.child_namespace,p_namespace) || '.' || l_partition_name,
                l_partition_name,
                coalesce(
                    replace(replace(rec.new_check, '#min#'::text, check_min::text), '#max#'::text, check_max::text),
                    '(' || p_timestamp_column || ' >= ' || check_min::text || ' and ' || p_timestamp_column || ' < ' || check_max::text || ')'
                ),
                l_primary_key,
                rec.parent_namespace || '.' || rec.parent_table,
                case rec.relpersistence
                    when 'u' then 'UNLOGGED'
                    when 't' then 'TEMPORARY'
                    else ''
                end
            );
            
            --raise notice 'sql_to_execute: %', sql_to_execute;
            execute sql_to_execute;
               
            for rec_index in csr_indexes (key := rec.parent_table)
            loop
                --raise notice 'nspname: %, relname: %,  create_index_sql: %', rec_index.nspname, rec_index.relname, rec_index.create_index_sql;
                sql_to_execute := split_part(rec_index.create_index_sql, ' ON ', 1) || ' ON ' || coalesce(rec.child_namespace,p_namespace) || '.' || replace(split_part(rec_index.create_index_sql, ' ON ', 2), rec.parent_namespace || '.', '');
                sql_to_execute := replace(split_part(sql_to_execute, ' USING ', 1), rec.parent_table, l_partition_name) || ' USING ' || split_part(sql_to_execute, ' USING ', 2);
                --raise notice 'sql_to_execute: %', sql_to_execute;
                execute sql_to_execute;
            end loop;
            
            for sql_trigger in (
                -- only update triggers on partition key column
                select
                    'CREATE TRIGGER ' || t.trigger_name || '_' || check_min::text || ' '
                        ||t.action_timing||' '||string_agg(distinct t.event_manipulation::text,' OR ')||' '
                        ||coalesce('OF '||string_agg(distinct event_object_column::text,',')||' ','')
                        ||'ON '||rec.child_namespace ||'.'||l_partition_name||' '
                        ||'FOR EACH '||t.action_orientation||' '
                        ||coalesce('WHEN '||t.action_condition||' ','')
                        ||t.action_statement||';'
                from information_schema.triggers t
                    left join information_schema.triggered_update_columns tc on tc.trigger_name = t.trigger_name
                where t.event_object_table = p_table_name
                    and t.action_timing = 'AFTER' -- only nested "AFTER" actions
                    --and exists (select 1 from information_schema.triggered_update_columns where trigger_name = t.trigger_name and event_object_column = p_timestamp_column)
                group by
                    t.trigger_schema,
                    t.trigger_name,
                    t.action_timing,
                    t.event_object_schema,
                    t.event_object_table,
                    t.action_orientation,
                    t.action_condition,
                    t.action_statement
            ) loop
                --raise notice 'sql_trigger: %', sql_trigger;
                execute sql_trigger;
            end loop;
        end loop;
    end loop;

end;
$fun$;

create or replace function sys.create_partitions(
	p_table_name varchar default null::varchar,
	p_current_datetime timestamp with time zone default null::timestamp with time zone,
	p_interval varchar default 'month'::varchar,
	p_timestamp_column varchar default 'creation_date'::varchar,
	p_namespace varchar default 'partitions'::varchar
) returns void
    security definer
    language plpgsql
as
$fun$
declare

    csr_tables  cursor (key varchar) for
                select distinct
                    c1.relpersistence,
                    n1.nspname as parent_namespace,
                    c1.relname as parent_table,
                    n2.nspname as child_namespace,
                    c2.relname as child_table,
                    regexp_replace(regexp_replace(pg_get_constraintdef(ch.oid,true),$$(?p)(>=[^\d]*)[\d\s:.+-]+$$, $$\1#min#$$, 'g'),$$(?p)(<[^\d]*)[\d\s:.+-]+$$, $$\1#max#$$, 'g') as new_check
                from pg_inherits i
                    join pg_class c1 on c1.oid = i.inhparent
                    join pg_namespace n1 on c1.relnamespace = n1.oid
                        and n1.oid in (
                            select oid from pg_namespace
                            where nspowner = sys.current_user_oid()
                                and nspname not like 'old_%'
                                and nspname not like 'new_%'
                            union
                            select oid from pg_namespace
                            where nspname in ('public','partitions','sys','sys_partitions','audit')
                        )
                    left join pg_class c2 on c2.oid = i.inhrelid
                        and c2.oid = (select max(inhrelid) from pg_inherits where inhparent = i.inhparent)
                    left join pg_namespace n2 on n2.oid = c2.relnamespace
                    
                    left join pg_constraint ch on ch.conrelid = c2.oid
                    
                    left join pg_trigger tr on tr.tgrelid = c2.oid
                where (key is null or c1.relname = key)
                order by n1.nspname, c1.relname, c2.relname;
    
    csr_indexes cursor (key varchar) for
                select
                    n.nspname,
                    c1.relname,
                    pg_get_indexdef(i.indexrelid) create_index_sql
                from pg_class c1
                    join pg_namespace n on c1.relnamespace = n.oid
                        and n.oid in (
                            select oid from pg_namespace
                            where nspowner = sys.current_user_oid()
                                and nspname not like 'old_%'
                                and nspname not like 'new_%'
                            union
                            select oid from pg_namespace
                            where nspname in ('public','partitions','sys','sys_partitions','audit')
                        )
                    join pg_index i on i.indrelid = c1.oid
                where c1.relname = key
                    and not i.indisprimary
                order by n.nspname, c1.relname;
    
    l_current_date timestamp with time zone = date_trunc(p_interval, current_date)::timestamp with time zone;
    l_partitions_to_create integer = 2;
    l_partition_name text;
    l_primary_key text;
    check_min timestamp with time zone;
    check_max timestamp with time zone;
    
    sql_to_execute text;
    sql_trigger text;

begin
    
    if p_table_name is not null
    and not sys.partition_exists(p_table_name)
    then
        raise exception 'Table % does not exist', p_table_name;
    end if;
    
    if p_current_datetime is not null then
        l_partitions_to_create := 1;
        l_current_date := date_trunc(p_interval, p_current_datetime)::timestamp with time zone;
    end if;
    
    raise notice 'Create partitions (DATE) for % (%)', coalesce(p_table_name,'ALL tables'), to_char(l_current_date,'YYYY-MM-DD HH24:MI');
    
    for rec in csr_tables (key := p_table_name)
    loop
        --raise notice 'table_name: %', rec.parent_table;
        --raise notice 'new_check: %', rec.new_check;
        
        for i in 0..l_partitions_to_create-1
        loop
            
            l_partition_name := sys.get_partition_name(rec.parent_table::text, l_current_date + (i::text||' '||p_interval)::interval,p_interval);
            check_min := l_current_date + (i::text||' '||p_interval)::interval;
            check_max := l_current_date + ((i+1)::text||' '||p_interval)::interval;
            
            if sys.partition_exists(l_partition_name) then
                continue;
            end if;
            
            select string_agg(x.column_name::text, ',' order by x.ordinal_position)
            into l_primary_key
            from (
                select kcu.column_name::text, kcu.ordinal_position
                from information_schema.tables t
                    join information_schema.table_constraints tc
                        on tc.table_catalog = t.table_catalog
                        and tc.table_schema = t.table_schema
                        and tc.table_name = t.table_name
                        and tc.constraint_type = 'PRIMARY KEY'
                    join information_schema.key_column_usage kcu
                        on kcu.table_catalog = tc.table_catalog
                        and kcu.table_schema = tc.table_schema
                        and kcu.table_name = tc.table_name
                        and kcu.constraint_name = tc.constraint_name
                where t.table_schema = rec.parent_namespace
                    and t.table_name = rec.parent_table
            ) x;
            
            sql_to_execute := format('CREATE %6$s TABLE IF NOT EXISTS %1$s('
                                        || case when l_primary_key > '' then 'CONSTRAINT %2$s_pkey PRIMARY KEY (%4$s),' else '' end
                                        || 'CONSTRAINT %2$s_timestamp_check CHECK %3$s'
                                    || ') INHERITS (%5$s); ALTER TABLE %1$s OWNER TO ' || current_user::text || ';',
                coalesce(rec.child_namespace,p_namespace) || '.' || l_partition_name,
                l_partition_name,
                coalesce(
                    --replace(replace(rec.new_check, '#min#'::text, to_char(check_min at time zone 'UTC','YYYYMMDD')), '#max#'::text, to_char(check_max at time zone 'UTC','YYYYMMDD')),
                    '(' || p_timestamp_column || ' >= ''' || to_char(check_min,'YYYYMMDD HH24:MI:SS') || '''::timestamp with time zone and ' || p_timestamp_column || ' < ''' || to_char(check_max,'YYYYMMDD HH24:MI:SS') || '''::timestamp with time zone)'
                ),
                l_primary_key,
                rec.parent_namespace || '.' || rec.parent_table,
                case rec.relpersistence
                    when 'u' then 'UNLOGGED'
                    when 't' then 'TEMPORARY'
                    else ''
                end
            );
            
            --raise notice 'sql_to_execute: %', sql_to_execute;
            execute sql_to_execute;
               
            for rec_index in csr_indexes (key := rec.parent_table)
            loop
                --raise notice 'p_namespace: %, child_namespace: %, nspname: %', p_namespace, rec.child_namespace, rec_index.nspname;
                --raise notice 'nspname: %, relname: %,  create_index_sql: %', rec_index.nspname, rec_index.relname, rec_index.create_index_sql;
                sql_to_execute := split_part(rec_index.create_index_sql, ' ON ', 1) || ' ON ' || coalesce(rec.child_namespace,p_namespace) || '.' || replace(split_part(rec_index.create_index_sql, ' ON ', 2), rec.parent_namespace || '.', '');
                sql_to_execute := replace(split_part(sql_to_execute, ' USING ', 1), rec.parent_table, l_partition_name) || ' USING ' || split_part(sql_to_execute, ' USING ', 2);
                --raise notice 'sql_to_execute: %', sql_to_execute;
                execute sql_to_execute;
            end loop;
            
            for sql_trigger in (
                -- only update triggers on partition key column
                select
                    'CREATE TRIGGER ' || t.trigger_name || '_' || to_char(check_min,'YYYYMMDDHH24') || ' '
                        ||t.action_timing||' '||string_agg(distinct t.event_manipulation::text,' OR ')||' '
                        ||coalesce('OF '||string_agg(distinct event_object_column::text,',')||' ','')
                        ||'ON '||rec.child_namespace ||'.'||l_partition_name||' '
                        ||'FOR EACH '||t.action_orientation||' '
                        ||coalesce('WHEN '||t.action_condition||' ','')
                        ||t.action_statement||';'
                from information_schema.triggers t
                    left join information_schema.triggered_update_columns tc on tc.trigger_name = t.trigger_name
                where t.event_object_table = p_table_name
                    and t.action_timing = 'AFTER' -- only nested "AFTER" actions
                    --and exists (select 1 from information_schema.triggered_update_columns where trigger_name = t.trigger_name and event_object_column = p_timestamp_column)
                group by
                    t.trigger_schema,
                    t.trigger_name,
                    t.action_timing,
                    t.event_object_schema,
                    t.event_object_table,
                    t.action_orientation,
                    t.action_condition,
                    t.action_statement
            ) loop
                --raise notice 'sql_trigger: %', sql_trigger;
                execute sql_trigger;
            end loop;
        end loop;
    end loop;

end;
$fun$;






create or replace function sys.pqm_dbcrontab_trigger ()
returns trigger
language 'plpgsql'
security definer
as
$body$
begin
    perform pg_notify(
            'pqm_dbcrontab_channel',
            json_build_object('queue', 'cron_changed')::text
        );
    
    return null;
end;
$body$
cost 0.1;

drop trigger if exists pqm_dbcrontab_triud on sys.pqm_dbcrontab;
create trigger pqm_dbcrontab_triud
  after insert or update or delete
  on sys.pqm_dbcrontab for each statement
  execute procedure sys.pqm_dbcrontab_trigger();


create function sys.pqm_log_trigger() returns trigger
    security definer
    cost 0
    language plpgsql
as
$$
declare
    p_relname text = 'pqm_log';
    c_relname text;
begin
    c_relname := sys.get_partition_name(p_relname, new.pqm_log_date, 'week');
    if not sys.partition_exists(c_relname) then
        perform sys.create_partitions(p_relname, new.pqm_log_date, 'week', 'pqm_log_date', 'sys_partitions');
    end if;
    
	execute 'insert into sys_partitions.' || c_relname || ' select $1.*' using new;
    
	return null;
end;
$$;

drop trigger if exists pqm_log_tri on sys.pqm_log;
create trigger pqm_log_tri
    before insert
    on sys.pqm_log
    for each row
execute procedure sys.pqm_log_trigger();







create or replace function sys.get_routine_kind(fname text) returns character
    security definer
    language plpgsql
as
$$
declare
    f text[] = regexp_split_to_array(fname,'\.');
begin
    if f[2] is null then f = array_prepend('public',f); end if;
    if version() ~* 'PostgreSQL 1\d.*' then
        return (
            select distinct prokind
            from pg_catalog.pg_proc p
                join pg_catalog.pg_namespace n on n.oid = p.pronamespace
            where n.nspname = f[1] and p.proname = f[2]
        );
    else
        return (
            select 'f'
            from pg_catalog.pg_proc p
                join pg_catalog.pg_namespace n on n.oid = p.pronamespace
            where n.nspname = f[1] and p.proname = f[2]
        );
    end if;
end;
$$;


