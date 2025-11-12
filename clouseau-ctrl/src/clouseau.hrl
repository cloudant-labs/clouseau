-record(clouseau_ctx, {
    name = undefined :: undefined | string(),
    cookie = undefined :: undefined | atom(),
    host = "127.0.0.1" :: string(),
    script_path = undefined :: string(),
    script_name = undefined :: string(),
    local = undefined :: atom(),
    remote = undefined :: atom(),
    format = table :: table | json
}).

-record(process_info, {
    properties = #{} :: #{atom() => term()}
}).

-record(meter_info, {
    properties = #{} :: #{atom() => term()}
}).
