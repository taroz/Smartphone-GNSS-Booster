cmake_minimum_required(VERSION 3.22.1)

project(rtcmjni C)

# Constellation/frequency macros must match the rtkrcv build on the server.
# rtcm3.c (decoder) is included for the encode->decode self-test round-trip.
add_definitions(-DENAGLO -DENAGAL -DENAQZS -DENACMP -DNFREQ=3 -DNEXOBS=0)

add_library(rtcmjni SHARED
        rtcm_jni.c
        rtklib/rtkcmn.c
        rtklib/rtcm.c
        rtklib/rtcm2.c
        rtklib/rtcm3e.c
        rtklib/rtcm3.c
        rtklib/rinex.c)

target_include_directories(rtcmjni PRIVATE rtklib)

# rinex.c is added only for outrnxobsh/outrnxobsb (RINEX OBS output from obsd_t). Its unused
# convrnx()/readrnx*() pull heavy decoders we don't include, so drop unreferenced functions with
# section GC; only the output path and its light rtkcmn.c callees survive.
target_compile_options(rtcmjni PRIVATE -ffunction-sections -fdata-sections)
target_link_options(rtcmjni PRIVATE -Wl,--gc-sections)

find_library(log-lib log)
target_link_libraries(rtcmjni ${log-lib})
