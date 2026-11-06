"""Run the production Lua scripts with a deterministic Redis command fixture.
python3 -m unittest discover -s src/test/python -v (system liblua5.4 required).
No live database/Redis is modified.
"""
import ctypes
import ctypes.util
from pathlib import Path
import re
import unittest

KOTLIN = Path(__file__).resolve().parents[2] / 'main/kotlin/com/nhom15/datxebackend'
SOURCE = (KOTLIN / 'matching/service/DispatchScripts.kt').read_text()
SOURCE += (KOTLIN / 'tracking/service/LocationService.kt').read_text()
LUA = ctypes.CDLL(ctypes.util.find_library('lua5.4'))
LUA.luaL_newstate.restype = ctypes.c_void_p
for name in ['luaL_openlibs', 'lua_close']:
    getattr(LUA, name).argtypes = [ctypes.c_void_p]
LUA.luaL_loadstring.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
LUA.lua_pcallk.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_longlong, ctypes.c_void_p]
LUA.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
LUA.lua_tolstring.restype = ctypes.c_char_p

FIXTURE = r'''
local db, expiry = {}, {}
NOW = 1000
local function get(key)
    if expiry[key] and expiry[key] <= NOW then db[key]=nil; expiry[key]=nil end
    return db[key]
end
redis = {call=function(cmd, key, ...)
    local a={...}; local v=get(key)
    if cmd=='GET' then return v or false end
    if cmd=='EXISTS' then return v and 1 or 0 end
    if cmd=='DEL' then db[key]=nil;expiry[key]=nil;return 1 end
    if cmd=='SET' then
        for _,arg in ipairs(a) do if arg=='NX' and v then return false end end
        db[key]=a[1];expiry[key]=nil
        for i=2,#a do
            if a[i]=='EX' then expiry[key]=NOW+tonumber(a[i+1])*1000 end
            if a[i]=='PX' then expiry[key]=NOW+tonumber(a[i+1]) end
        end
        return 'OK'
    end
    if cmd=='PSETEX' then db[key]=a[2];expiry[key]=NOW+tonumber(a[1]);return 'OK' end
    if cmd=='EXPIRE' then if v then expiry[key]=NOW+tonumber(a[1])*1000 end;return 1 end
    if cmd=='HGET' then return v and v[a[1]] or false end
    if cmd=='HEXISTS' then return v and v[a[1]] and 1 or 0 end
    if cmd=='HSET' then
        v=v or {};db[key]=v
        for i=1,#a,2 do v[a[i]]=a[i+1] end;return 1
    end
    if cmd=='HDEL' then if v then for _,f in ipairs(a) do v[f]=nil end end;return 1 end
    if cmd=='HINCRBY' then v=v or {};db[key]=v;v[a[1]]=tonumber(v[a[1]] or 0)+tonumber(a[2]);return v[a[1]] end
    if cmd=='SISMEMBER' then return v and v[a[1]] and 1 or 0 end
    if cmd=='SADD' then v=v or {};db[key]=v;for _,f in ipairs(a) do v[f]=true end;return 1 end
    if cmd=='ZSCORE' then return v and v[a[1]] or false end
    if cmd=='ZADD' then v=v or {};db[key]=v;v[a[2]]=tonumber(a[1]);return 1 end
    if cmd=='ZREM' then if v then v[a[1]]=nil end;return 1 end
    if cmd=='GEOADD' then v=v or {};db[key]=v;v[a[3]]={a[1],a[2]};return 1 end
    if cmd=='LLEN' then return v and #v or 0 end
    if cmd=='RPOP' then if not v then return false end;return table.remove(v) or false end
    if cmd=='LPUSH' then v=v or {};db[key]=v;for _,f in ipairs(a) do table.insert(v,1,f) end;return #v end
    if cmd=='XADD' then return 'event' end
    error('Unhandled command '..cmd)
end}
local scripts={}
SCRIPT_DEFINITIONS
local function run(name,keys,args) KEYS=keys;ARGV=args;return scripts[name]() end
local function seed(ride)
    ride=ride or '42'
    redis.call('SET','driver:alive:7','1','PX',30000)
    redis.call('SET','driver:7:status','ONLINE')
    redis.call('HSET','driver:7:location','lat','21.03','lng','105.84')
    redis.call('SET','ride:'..ride..':tick','lease','PX',5000)
    run('INITIALIZE_SEARCH',{'ride:'..ride..':state'},{'SEARCHING','1','AUTO','CAR','21.03','105.84','120','0'})
    redis.call('LPUSH','ride:'..ride..':candidates','7')
end
local function offer(ride,token)
    ride=ride or '42';token=token or 'offer-a'
    return run('OFFER_NEXT',{'ride:'..ride..':state','ride:'..ride..':candidates','ride:'..ride..':rejected','timeouts'},
        {ride,tostring(NOW+12000),'12000','120',token,'lease'})
end
local function accept(token,time)
    return run('ACCEPT',{'ride:42:state','timeouts','ride:42:candidates'},{'7','42',tostring(time or NOW),token or 'offer-a'})
end
local function release(token)
    return run('RELEASE',{'ride:42:state','timeouts','ride:42:rejected','available_drivers'},
        {'7','42','ignored','120',token or 'offer-a'})
end
'''


def execute(scenario):
    definitions = []
    for name, body in re.findall(r'val (\w+) = DefaultRedisScript<[^>]+>\(\s*"""(.*?)"""', SOURCE, re.S):
        definitions.append(f'scripts.{name}=function()\n{body}\nend')
    script = FIXTURE.replace('SCRIPT_DEFINITIONS', '\n'.join(definitions)) + '\n' + scenario
    state = LUA.luaL_newstate()
    try:
        LUA.luaL_openlibs(state)
        code = LUA.luaL_loadstring(state, script.encode())
        if code == 0:
            code = LUA.lua_pcallk(state, 0, 0, 0, 0, None)
        if code:
            raise AssertionError(LUA.lua_tolstring(state, -1, None).decode())
    finally:
        LUA.lua_close(state)


class DispatchPresenceTests(unittest.TestCase):
    def test_sequential_offer_and_driver_exclusion_across_rides(self):
        execute("""seed();assert(offer()=='7'); assert(offer()=='WAIT')
            seed('43');assert(offer('43')=='ROUND_EXHAUSTED')
            assert(redis.call('GET','driver:7:lock')=='42')""")

    def test_accept_requires_exact_token_and_both_leases(self):
        execute("""seed();offer();assert(accept('old-offer')==0);assert(accept()==1)
            assert(accept()==0);assert(release()==0)
            assert(redis.call('GET','driver:7:status')=='BUSY')
            assert(redis.call('GET','driver:7:current_ride')=='42')
            assert(not redis.call('GET','ride:42:current_offer'))""")
        for missing in ['driver:7:lock', 'ride:42:current_offer']:
            with self.subTest(missing=missing):
                execute(f"seed();offer();redis.call('DEL','{missing}');assert(accept()==0)")

    def test_server_deadline_and_expired_ttl_cannot_be_accepted(self):
        execute("seed();offer();assert(accept('offer-a',13000)==0);NOW=13001;assert(accept()==0)")

    def test_old_timeout_does_not_touch_current_offer(self):
        execute("""seed();offer();assert(release('previous-token')==0)
            assert(redis.call('GET','driver:7:lock')=='42');assert(accept()==1)""")

    def test_cancel_release_and_rollback_only_restore_live_driver(self):
        for name in ['RELEASE', 'CANCEL_SEARCH', 'ROLLBACK_ACCEPT']:
            for alive in [True, False]:
                with self.subTest(script=name, alive=alive):
                    if name == 'RELEASE':
                        action = 'assert(release()==1)'
                    elif name == 'CANCEL_SEARCH':
                        action = "assert(run('CANCEL_SEARCH',{'ride:42:state','timeouts','ride:42:candidates','available_drivers'},{'42'})=='7')"
                    else:
                        action = "assert(accept()==1);assert(run('ROLLBACK_ACCEPT',{'ride:42:state','timeouts','ride:42:rejected','ride:42:candidates','available_drivers'},{'7','42','SEARCHING','120'})==1)"
                    execute(f"""seed();offer();{'redis.call("DEL","driver:alive:7")' if not alive else ''}
                        {action}
                        assert(redis.call('GET','driver:7:status')=='{'ONLINE' if alive else 'OFFLINE'}')
                        assert(not redis.call('GET','driver:7:lock'))
                        assert((get('available_drivers') and get('available_drivers')['7'] ~= nil or false)=={str(alive).lower()})""")

    def test_rejected_driver_is_not_offered_again(self):
        execute("""seed();offer();release();redis.call('LPUSH','ride:42:candidates','7')
            assert(offer()=='ROUND_EXHAUSTED')""")

    def test_release_must_not_delete_another_rides_new_lock(self):
        execute("""seed();offer();NOW=14000;redis.call('SET','driver:7:lock','43','PX',12000)
            redis.call('SET','driver:7:status','OFFERED');assert(release()==1)
            assert(redis.call('GET','driver:7:lock')=='43');assert(redis.call('GET','driver:7:status')=='OFFERED')""")

    def test_expired_tick_cannot_publish_and_last_radius_keeps_rescanning(self):
        execute("""seed();NOW=6001;assert(offer()=='WAIT')
            redis.call('DEL','ride:42:candidates')
            redis.call('HSET','ride:42:state','nextRoundIndex','9')
            assert(run('ADVANCE_ROUND',{'ride:42:state','ride:42:candidates'},{'2','3','5'})=='9:5')""")

    def test_idle_reconcile_cannot_free_driver_owned_by_new_ride(self):
        execute("""seed();offer();accept();
            assert(run('RECONCILE_IDLE',{'available_drivers'},{'7','41'})==0)
            assert(redis.call('GET','driver:7:status')=='BUSY')
            assert(run('RECONCILE_IDLE',{'available_drivers'},{'7','42'})==1)
            assert(redis.call('GET','driver:7:status')=='ONLINE')""")

    def test_delayed_gps_cannot_replace_a_newer_fix_or_extend_its_ttl(self):
        execute("""
            local location='driver:7:location'
            local alive='driver:alive:7'
            local keys={location,alive,'lastseen'}
            assert(run('SAVE_SAMPLE',keys,{'7','21.04','105.85','new','1100','1000'})==1)
            NOW=1500
            assert(run('SAVE_SAMPLE',keys,{'7','21.03','105.84','old','1000','1000'})==0)
            assert(redis.call('HGET',location,'sampledAt')=='new')
            assert(redis.call('GET',alive)=='1')
            NOW=2000
            assert(redis.call('GET',alive)==false)
        """)

    def test_fresh_gps_cannot_put_an_offered_or_busy_driver_back_in_geo(self):
        execute("""seed();assert(run('ADD_AVAILABLE',{'available_drivers'},{'7','105.84','21.03'})==1)
            offer();assert(run('ADD_AVAILABLE',{'available_drivers'},{'7','105.84','21.03'})==0)
            accept();assert(run('ADD_AVAILABLE',{'available_drivers'},{'7','105.84','21.03'})==0)""")
