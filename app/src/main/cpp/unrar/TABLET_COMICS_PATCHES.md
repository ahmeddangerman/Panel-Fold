# Local UnRAR adapter extensions

These source changes support the app's bounded, cancellable in-memory reader:

- `dll.hpp` / `dll.cpp`: `RARSetDictionaryLimit(handle, bytes)` sets both
  threshold fields, removing the engine's implicit 32 MiB allowance. The
  `UCM_PROGRESS` callback message is an app extension without output bytes.
- `rdwrfn.cpp`: poll `UCM_PROGRESS` while reading packed bytes and writing
  discarded solid output. Standard `UCM_PROCESSDATA` remains disabled for
  `RAR_SKIP`; callers do not receive skipped output as page content.
- `extract.cpp`: check the actual minimum 256 KiB unpack window before
  allocation; stored entries do not need an unpack window.

The adapter in `../rar_session.cpp` serializes engine entry points because
UnRAR uses a process-global error handler. It keeps each archive's cursor and
solid dictionary alive across forward page reads. An interrupted extraction is
closed and reconstructed on the next request. Run `app/src/test/native/run.sh`
from the repository root for regression coverage.

UnRAR's original license and restrictions remain in `license.txt`.
