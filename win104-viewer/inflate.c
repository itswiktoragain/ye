/* 16-bit, memory-bounded DEFLATE stream (RFC 1951).
   Written for the WIN104 project; no 32-bit OS or zlib dependency. */
#include "viewer.h"

#define WINSIZE 32768
typedef struct {
    unsigned short count[16];
    unsigned short first[16];
    unsigned short offset[16];
    unsigned short symbol[288];
} HUFF;

static HUFF lit, dis, clen;
static int (*input_byte)(void *);
static void *input_ctx;
static unsigned char __far *history;
static unsigned long bitbuf;
static unsigned short bits, winpos, copylen, copydist, storedleft;
static int last, mode, stopped, bad;

static unsigned getbits(unsigned n) {
    unsigned v;
    while (bits < n) {
        int c = input_byte(input_ctx);
        if (c < 0) {
            bad=1;
            return 0;
        }
        bitbuf |= ((unsigned long)(unsigned char)c) << bits;
        bits += 8;
    }
    v=(unsigned)(bitbuf & ((1UL<<n)-1));
    bitbuf >>= n;
    bits=(unsigned short)(bits-n);
    return v;
}
static void make_huff(HUFF *h, const unsigned char *lens, unsigned n) {
    unsigned i, j, k;
    unsigned short next[16];
    unsigned code=0, sum=0;
    for (i=0; i<16; ++i) h->count[i]=0;
    for (i=0; i<n; ++i) if (lens[i] <= 15 && lens[i]) h->count[lens[i]]++;
    for (i=1; i<=15; ++i) {
        h->offset[i]=(unsigned short)sum;
        next[i]=(unsigned short)sum;
        sum += h->count[i];
        code=(code+h->count[i-1])*2;
        h->first[i]=(unsigned short)code;
    }
    for (j=0; j<n; ++j) {
        k=lens[j];
        if(k && k<=15) h->symbol[next[k]++]=(unsigned short)j;
    }
}
static int decode(HUFF *h) {
    unsigned len, code=0, base;
    for (len=1; len<=15; ++len) {
        code=(code<<1)|getbits(1);
        base=h->first[len];
        if (code>=base && code-base<h->count[len])
            return h->symbol[h->offset[len]+code-base];
        if (bad) return -1;
    }
    bad=1;
    return -1;
}
static int new_block(void) {
    unsigned i, j, nlit, ndist, ncode, ix, run, val;
    unsigned char lengths[320], codes[19];
    static const unsigned char order[19]={
        16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15
    };
    unsigned hdr=getbits(3);
    last=(int)(hdr&1);
    mode=(int)((hdr>>1)&3);
    if(bad || mode==3) return -1;
    if(mode==0) {
        bits=(unsigned short)(bits-(bits&7));
        bitbuf >>= 0; /* bits remaining are already LSB-aligned */
        val=getbits(16);
        if ((val ^ getbits(16)) != 65535U) return -1;
        storedleft=(unsigned short)val;
        return 0;
    }
    if(mode==1) {
        for(i=0;i<288;i++) lengths[i]=(unsigned char)(i<144?8:i<256?9:i<280?7:8);
        make_huff(&lit,lengths,288);
        for(i=0;i<32;i++) lengths[i]=5;
        make_huff(&dis,lengths,32);
        return 0;
    }
    nlit=getbits(5)+257;
    ndist=getbits(5)+1;
    ncode=getbits(4)+4;
    if(bad || nlit>286 || ndist>32) return -1;
    for(i=0;i<19;i++) codes[i]=0;
    for(i=0;i<ncode;i++) codes[order[i]]=(unsigned char)getbits(3);
    make_huff(&clen,codes,19);
    ix=0;
    while (ix<nlit+ndist) {
        int s=decode(&clen);
        if(s<0) return -1;
        if(s<=15) {
            lengths[ix++]=(unsigned char)s;
        } else {
            if(s==16) {
                if(ix==0) return -1;
                run=getbits(2)+3;
                val=lengths[ix-1];
            } else if (s==17) {
                run=getbits(3)+3; val=0;
            } else if (s==18) {
                run=getbits(7)+11; val=0;
            } else return -1;
            if(ix+run>nlit+ndist) return -1;
            for(j=0;j<run;j++) lengths[ix++]=(unsigned char)val;
        }
        if(bad) return -1;
    }
    make_huff(&lit,lengths,nlit);
    make_huff(&dis,lengths+nlit,ndist);
    return 0;
}
static int emit(unsigned c) {
    history[winpos]=(unsigned char)c;
    winpos=(unsigned short)((winpos+1)&32767);
    return (int)(c&255);
}
void inflate_begin(int (*fn)(void *), void *ctx, unsigned char __far *window) {
    input_byte=fn; input_ctx=ctx; history=window;
    bitbuf=0; bits=0; winpos=0; copylen=0; copydist=0;
    storedleft=0; last=0; mode=-1; stopped=0; bad=0;
}
int inflate_byte(void) {
    static const unsigned short lbase[29]={
        3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,19,23,27,31,
        35,43,51,59,67,83,99,115,131,163,195,227,258
    };
    static const unsigned char lextra[29]={
        0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0
    };
    static const unsigned short dbase[30]={
        1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,
        513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577
    };
    static const unsigned char dextra[30]={
        0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,
        10,10,11,11,12,12,13,13
    };
    int sym, dsym;
    if(stopped) return bad ? -2 : -1;
    for (;;) {
        if (bad) {stopped=1;return -2;}
        if (copylen) {
            int c=history[(unsigned short)((winpos-copydist)&32767)];
            copylen--;
            return emit((unsigned)c);
        }
        if (mode<0) {
            if(new_block()<0) {stopped=bad=1;return -2;}
        }
        if(mode==0) {
            if(storedleft) {
                storedleft--;
                return emit(getbits(8));
            }
            mode=-1;
            if(last) {stopped=1;return -1;}
            continue;
        }
        sym=decode(&lit);
        if(sym<0) {stopped=bad=1;return -2;}
        if(sym<256) return emit((unsigned)sym);
        if(sym==256) {
            mode=-1;
            if(last) {stopped=1;return -1;}
            continue;
        }
        if(sym>285) {stopped=bad=1;return -2;}
        sym-=257;
        copylen=(unsigned short)(lbase[sym]+getbits(lextra[sym]));
        dsym=decode(&dis);
        if(dsym<0 || dsym>=30) {stopped=bad=1;return -2;}
        copydist=(unsigned short)(dbase[dsym]+getbits(dextra[dsym]));
        if(copydist==0 || bad) {stopped=bad=1;return -2;}
    }
}
