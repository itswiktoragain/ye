#ifndef CV_CODECS_H
#define CV_CODECS_H
#include <windows.h>
/* Low-memory image buffer and palette output: one byte/pixel, max 128x128. */
#define CV_CODECS_MAX_PIXELS 16384UL
extern HGLOBAL cv_codec_handle;
extern unsigned char __far *cv_codec_pixels;
extern unsigned cv_codec_width,cv_codec_height;
extern unsigned cv_codec_source_width,cv_codec_source_height;

int cv_codec_begin(unsigned src_width,unsigned src_height);
void cv_codec_release(void);
void cv_codec_put(unsigned x,unsigned y,unsigned r,unsigned g,unsigned b);
int cv_decode_jpeg(HFILE file);
int cv_decode_png(HFILE file);
int cv_decode_webp(HFILE file);
#endif
