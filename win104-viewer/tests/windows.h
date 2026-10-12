#ifndef WIN104_NATIVE_TEST_WINDOWS_H
#define WIN104_NATIVE_TEST_WINDOWS_H
#include <stdlib.h>
#include <stddef.h>
#include <unistd.h>
#include <sys/types.h>
#include <stdint.h>
#define __far
#define __huge
#define GMEM_MOVEABLE 2
typedef int HFILE;
typedef void *HGLOBAL;
typedef unsigned long DWORD;
typedef unsigned UINT;
static HGLOBAL GlobalAlloc(unsigned type,unsigned long bytes) {
 (void)type;
 return malloc(bytes);
}
static unsigned char *GlobalLock(HGLOBAL handle) {return (unsigned char *)handle;}
static unsigned GlobalUnlock(HGLOBAL handle) {(void)handle;return 0;}
static HGLOBAL GlobalFree(HGLOBAL handle) {free(handle);return 0;}
static unsigned _lread(HFILE f,void *dest,unsigned n) {
 ssize_t readbytes=read(f,dest,n);
 return readbytes<0?0:(unsigned)readbytes;
}
static long _llseek(HFILE f,long pos,int whence) {
 off_t result=lseek(f,(off_t)pos,whence);
 return (long)result;
}
#endif
