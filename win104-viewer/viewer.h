#ifndef CHRONOVIEW_H
#define CHRONOVIEW_H
#include <windows.h>
#include <stdio.h>
typedef unsigned char U8;
extern U8 __far *image_pixels;
extern unsigned image_w, image_h, actual_w, actual_h;
extern char format_name[18];
extern const unsigned char ega_rgb[16][3];
void image_dimensions(unsigned w, unsigned h);
void image_set(unsigned x,unsigned y,unsigned r,unsigned g,unsigned b);
int image_load(const char *name);
void inflate_begin(int (*fn)(void *), void *ctx, U8 __far *window);
int inflate_byte(void);
#endif
