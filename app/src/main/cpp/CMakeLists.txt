cmake_minimum_required(VERSION 3.22.1)
# NDK r27 supports flexible page sizes; explicit linker alignment also covers
# toolchains which evaluate their Android options before this project file.
set(ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES ON)
project(tablet_unrar LANGUAGES C CXX)

set(UNRAR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/unrar)
set(UNRAR_SOURCES
    strlist.cpp strfn.cpp pathfn.cpp smallfn.cpp global.cpp file.cpp filefn.cpp
    filcreat.cpp archive.cpp arcread.cpp unicode.cpp system.cpp crypt.cpp crc.cpp
    rawread.cpp encname.cpp resource.cpp match.cpp timefn.cpp rdwrfn.cpp consio.cpp
    options.cpp errhnd.cpp rarvm.cpp secpassword.cpp rijndael.cpp getbits.cpp sha1.cpp
    sha256.cpp blake2s.cpp hash.cpp extinfo.cpp extract.cpp volume.cpp list.cpp find.cpp
    unpack.cpp headers.cpp threadpool.cpp rs16.cpp cmddata.cpp ui.cpp largepage.cpp
    filestr.cpp scantree.cpp dll.cpp qopen.cpp)
list(TRANSFORM UNRAR_SOURCES PREPEND "${UNRAR_DIR}/")

add_library(unrar_engine STATIC ${UNRAR_SOURCES})
target_include_directories(unrar_engine PUBLIC ${UNRAR_DIR})
target_compile_definitions(unrar_engine PRIVATE RARDLL SILENT _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE RAR_SMP)
target_compile_options(unrar_engine PRIVATE -fPIC -fexceptions -Wno-logical-op-parentheses -Wno-switch -Wno-dangling-else)
set_target_properties(unrar_engine PROPERTIES CXX_STANDARD 11 CXX_STANDARD_REQUIRED ON)

add_library(tablet_unrar SHARED native_unrar.cpp rar_session.cpp)
target_include_directories(tablet_unrar PRIVATE ${UNRAR_DIR})
target_compile_definitions(tablet_unrar PRIVATE RARDLL)
target_compile_options(tablet_unrar PRIVATE -fexceptions)
target_link_options(tablet_unrar PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
set_target_properties(tablet_unrar PROPERTIES CXX_STANDARD 11 CXX_STANDARD_REQUIRED ON)
target_link_libraries(tablet_unrar PRIVATE unrar_engine)
