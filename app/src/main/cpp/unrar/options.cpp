#include "rar.hpp"

// Set all non-default values here.
// Default values are set from CommandData::Init().
void RAROptions::Init()
{
  WinSize=0x2000000;
  // Tablet Comics supplies a per-extraction limit from current device memory
  // through UCM_LARGEDICT. Do not let UnRAR's 4 GiB default bypass that guard.
  WinSizeLimit=0;
  Overwrite=OVERWRITE_DEFAULT;
  Method=3;
  MsgStream=MSG_STDOUT;
  ConvertNames=NAMES_ORIGINALCASE;
  xmtime=EXTTIME_MAX;
  FileSizeLess=INT64NDF;
  FileSizeMore=INT64NDF;
  HashType=HASH_CRC32;
#ifdef RAR_SMP
  Threads=GetNumberOfThreads();
#endif
#ifdef USE_QOPEN
  QOpenMode=QOPEN_AUTO;
#endif
}
