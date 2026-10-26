# Native archive regression probe

This host test exercises the same `rar_session.cpp` and vendored UnRAR engine
used by JNI, including compressed RAR4/RAR5, solid forward cursor reuse,
backwards reads, cancellation **inside** skipped decompression without output
callbacks, cancellation recovery, a dictionary budget below 32 MiB (and below
the engine's actual 256 KiB minimum), and close racing an active read.

Run `./app/src/test/native/run.sh` from the repository root. It builds in `/tmp`
and does not invoke Gradle. Android instrumentation additionally exercises JNI.

Fixtures are under `app/src/androidTest/assets/native`:

- `compressed-rar4.rar`: libarchive `test_read_format_rar_compress_normal.rar`
- `compressed-rar5.rar`: libarchive `test_read_format_rar5_compressed.rar`
- `solid-rar5.rar`: libarchive `test_read_format_rar5_solid.rar`
- `solid-rar4.rar`: Junrar `src/test/resources/com/github/junrar/solid/rar4-solid.rar`
- `duplicate.rar`: synthetic stored RAR4 with two different `page.png` entries

Public upstream fixture locations: https://github.com/libarchive/libarchive/tree/master/libarchive/test
and https://github.com/junrar/junrar/tree/master/src/test/resources/com/github/junrar/solid.
libarchive and Junrar distribute these fixtures under their BSD licenses.
