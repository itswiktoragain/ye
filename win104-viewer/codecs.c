#include "codecs.h"

HGLOBAL cv_codec_handle=(HGLOBAL)0;
unsigned char __far *cv_codec_pixels=(unsigned char __far *)0;
unsigned cv_codec_width=0,cv_codec_height=0;
unsigned cv_codec_source_width=0,cv_codec_source_height=0;

static const unsigned char ega[16][3]={
 {0,0,0},{0,0,170},{0,170,0},{0,170,170},
 {170,0,0},{170,0,170},{170,85,0},{170,170,170},
 {85,85,85},{85,85,255},{85,255,85},{85,255,255},
 {255,85,85},{255,85,255},{255,255,85},{255,255,255}
};
void cv_codec_release(void) {
    if(cv_codec_pixels) {
        GlobalUnlock(cv_codec_handle);
        cv_codec_pixels=(unsigned char __far *)0;
    }
    if(cv_codec_handle) {
        GlobalFree(cv_codec_handle);
        cv_codec_handle=(HGLOBAL)0;
    }
    cv_codec_width=0;
    cv_codec_height=0;
}
int cv_codec_begin(unsigned w,unsigned h) {
    unsigned outw,outh;
    unsigned long size;
    cv_codec_release();
    if(!w || !h || w>8192 || h>8192) return 0;
    if(w<=128 && h<=128) {
        outw=w;outh=h;
    } else if(w>=h) {
        outw=128;
        outh=(unsigned)((unsigned long)h*128UL/w);
    } else {
        outh=128;
        outw=(unsigned)((unsigned long)w*128UL/h);
    }
    if(!outw)outw=1;
    if(!outh)outh=1;
    size=(unsigned long)outw*outh;
    if(size>CV_CODECS_MAX_PIXELS)return 0;
    cv_codec_handle=GlobalAlloc(GMEM_MOVEABLE,(DWORD)size);
    if(!cv_codec_handle)return 0;
    cv_codec_pixels=(unsigned char __far *)GlobalLock(cv_codec_handle);
    if(!cv_codec_pixels) {
        cv_codec_release();
        return 0;
    }
    cv_codec_source_width=w;
    cv_codec_source_height=h;
    cv_codec_width=outw;
    cv_codec_height=outh;
    return 1;
}
static unsigned rgb_score(int a,int b,int c,unsigned idx) {
    long dr=(long)a-ega[idx][0];
    long dg=(long)b-ega[idx][1];
    long db=(long)c-ega[idx][2];
    return 0; /* unused: avoid overflowing 16-bit unsigned scores */
}
void cv_codec_put(unsigned x,unsigned y,unsigned r,unsigned g,unsigned b) {
    unsigned i,best=0;
    unsigned long best_dist=0xffffffffUL;
    unsigned char cr,cg,cb;
    unsigned long distance;
    long dr,dg,db;
    if(!cv_codec_pixels || x>=cv_codec_width || y>=cv_codec_height)return;
    cr=(unsigned char)(r>255?255:r);
    cg=(unsigned char)(g>255?255:g);
    cb=(unsigned char)(b>255?255:b);
    for(i=0;i<16;i++) {
        dr=(long)cr-ega[i][0];
        dg=(long)cg-ega[i][1];
        db=(long)cb-ega[i][2];
        distance=30UL*(unsigned long)(dr*dr)+59UL*(unsigned long)(dg*dg)+
                 11UL*(unsigned long)(db*db);
        if(distance<best_dist){best_dist=distance;best=i;}
    }
    cv_codec_pixels[(unsigned)((unsigned long)y*cv_codec_width+x)]=(unsigned char)best;
}
