/* Native PNG streaming decoder for 8086 / Windows 1.0.
 * PNG input is read from KERNEL._lread. Only one scanline plus its
 * predecessor, a 32KiB DEFLATE history, and <=16KiB indexed EGA output
 * are stored. All buffers are allocated with GlobalAlloc so the 64K
 * near-DGROUP cannot be exhausted.
 *
 * Supports PNG types 0,2,3,4,6 at valid depths 1/2/4/8/16,
 * PLTE, alpha, PNG filters 0-4, zlib blocks 0/1/2.
 * Excludes Adam7 interlacing. No DOS INT 21h or stdio operations.
 */
#include "codecs.h"

#define PNG_WINSIZE 32768U
#define PNG_CHUNK_LIMIT 16777216UL

typedef struct {
    unsigned short count[16];
    unsigned short first[16];
    unsigned short offset[16];
    unsigned short symbol[288];
} PNG_HUFF;

static PNG_HUFF ph_lit,ph_dist,ph_code;
static HFILE ph_file;
static unsigned char ph_filebuf[512];
static unsigned ph_filepos,ph_filecount;
static unsigned long ph_idat_remaining,ph_total_out;
static unsigned long ph_bitbuf;
static unsigned ph_bitcount,ph_histpos,ph_copylen,ph_copydist;
static unsigned ph_storedleft,ph_last,ph_mode,ph_bad,ph_done;
static HGLOBAL ph_history_handle;
static unsigned char __far *ph_history;

static unsigned ph_width,ph_height,ph_type,ph_depth;
static unsigned ph_channels,ph_bpp,ph_stride;
static HGLOBAL ph_currhandle,ph_prevhandle;
static unsigned char __far *ph_curr,*ph_prev;
static unsigned char ph_palette[256][4];
static unsigned ph_palette_count;
static unsigned ph_gray_transparent,ph_rgb_transparent;
static unsigned ph_trns_gray,ph_trns_r,ph_trns_g,ph_trns_b;

static int ph_filebyte(void) {
    unsigned got;
    if(ph_filepos>=ph_filecount) {
        got=_lread(ph_file,(void __huge *)ph_filebuf,512);
        if(got==0 || got>512)return -1;
        ph_filecount=got;
        ph_filepos=0;
    }
    return ph_filebuf[ph_filepos++];
}
static int ph_skip(unsigned long count) {
    while(count--)if(ph_filebyte()<0)return 0;
    return 1;
}
static unsigned long ph_be32(void) {
    unsigned long n=0;
    unsigned k;
    int b;
    for(k=0;k<4;k++) {
        b=ph_filebyte();
        if(b<0){ph_bad=1;return 0;}
        n=(n<<8)|(unsigned long)b;
    }
    return n;
}
static unsigned ph_be16(void) {
    int a=ph_filebyte(),b=ph_filebyte();
    if(a<0 || b<0){ph_bad=1;return 0;}
    return (unsigned)((a<<8)|b);
}
static int ph_chunk(unsigned long *len,unsigned char name[5]) {
    unsigned i;
    int c;
    *len=ph_be32();
    if(ph_bad || *len>PNG_CHUNK_LIMIT)return 0;
    for(i=0;i<4;i++) {
        c=ph_filebyte();
        if(c<0)return 0;
        name[i]=(unsigned char)c;
    }
    name[4]=0;
    return 1;
}
static int ph_is(const unsigned char *a,const char *name) {
    unsigned i;
    for(i=0;i<4;i++)if(a[i]!=(unsigned char)name[i])return 0;
    return 1;
}
static int ph_next_zbyte(void) {
    unsigned char tag[5];
    unsigned long size;
    int c;
    while(!ph_idat_remaining) {
        /* End of one PNG IDAT chunk -> CRC and next IDAT header. */
        if(!ph_skip(4))return -1;
        if(!ph_chunk(&size,tag))return -1;
        if(!ph_is(tag,"IDAT"))return -1;
        ph_idat_remaining=size;
    }
    c=ph_filebyte();
    if(c<0)return -1;
    ph_idat_remaining--;
    return c;
}
static unsigned ph_bits(unsigned n) {
    unsigned ret;
    int c;
    while(ph_bitcount<n) {
        c=ph_next_zbyte();
        if(c<0){ph_bad=1;return 0;}
        ph_bitbuf |= (unsigned long)(unsigned char)c << ph_bitcount;
        ph_bitcount+=8;
    }
    ret=(unsigned)(ph_bitbuf & ((1UL<<n)-1));
    ph_bitbuf>>=n;
    ph_bitcount-=n;
    return ret;
}
static void ph_huff(PNG_HUFF *h,const unsigned char *lengths,unsigned n) {
    unsigned i,k,code=0,sum=0;
    unsigned short next[16];
    for(i=0;i<16;i++)h->count[i]=0;
    for(i=0;i<n;i++)if(lengths[i]>0 && lengths[i]<=15)h->count[lengths[i]]++;
    for(i=1;i<=15;i++) {
        code=(code+h->count[i-1])*2;
        h->first[i]=(unsigned short)code;
        h->offset[i]=(unsigned short)sum;
        next[i]=(unsigned short)sum;
        sum+=h->count[i];
    }
    for(i=0;i<n;i++) {
        k=lengths[i];
        if(k && k<=15)h->symbol[next[k]++]=(unsigned short)i;
    }
}
static int ph_symbol(PNG_HUFF *h) {
    unsigned len,code=0,base;
    for(len=1;len<=15;len++) {
        code=(code<<1)|ph_bits(1);
        if(ph_bad)return -1;
        base=h->first[len];
        if(code>=base && code-base<h->count[len])
            return h->symbol[h->offset[len]+code-base];
    }
    ph_bad=1;
    return -1;
}
static int ph_block(void) {
    static const unsigned char order[19]={
        16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15
    };
    unsigned i,j,nlit,ndist,ncode,index,run,value;
    unsigned char lengths[320],codes[19];
    unsigned hdr=ph_bits(3);
    if(ph_bad)return 0;
    ph_last=hdr&1;
    ph_mode=(hdr>>1)&3;
    if(ph_mode==3)return 0;
    if(ph_mode==0) {
        unsigned drop=ph_bitcount & 7;
        ph_bitbuf>>=drop;
        ph_bitcount-=drop;
        value=ph_bits(16);
        if((value^ph_bits(16))!=65535U)return 0;
        ph_storedleft=value;
        return !ph_bad;
    }
    if(ph_mode==1) {
        for(i=0;i<288;i++)
            lengths[i]=(unsigned char)(i<144?8:i<256?9:i<280?7:8);
        ph_huff(&ph_lit,lengths,288);
        for(i=0;i<32;i++)lengths[i]=5;
        ph_huff(&ph_dist,lengths,32);
        return 1;
    }
    nlit=ph_bits(5)+257;
    ndist=ph_bits(5)+1;
    ncode=ph_bits(4)+4;
    if(ph_bad || nlit>286 || ndist>32)return 0;
    for(i=0;i<19;i++)codes[i]=0;
    for(i=0;i<ncode;i++)codes[order[i]]=(unsigned char)ph_bits(3);
    ph_huff(&ph_code,codes,19);
    index=0;
    while(index<nlit+ndist) {
        int s=ph_symbol(&ph_code);
        if(s<0)return 0;
        if(s<=15)lengths[index++]=(unsigned char)s;
        else {
            if(s==16) {
                if(index==0)return 0;
                run=ph_bits(2)+3;
                value=lengths[index-1];
            } else if(s==17) {
                run=ph_bits(3)+3;value=0;
            } else if(s==18) {
                run=ph_bits(7)+11;value=0;
            } else return 0;
            if(index+run>nlit+ndist)return 0;
            for(j=0;j<run;j++)lengths[index++]=(unsigned char)value;
        }
        if(ph_bad)return 0;
    }
    if(!lengths[256])return 0;
    ph_huff(&ph_lit,lengths,nlit);
    ph_huff(&ph_dist,lengths+nlit,ndist);
    return 1;
}
static int ph_emit(unsigned c) {
    ph_history[ph_histpos]=(unsigned char)c;
    ph_histpos=(ph_histpos+1)&32767;
    ph_total_out++;
    return (int)(c&255);
}
static int ph_inflate(void) {
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
    int s,d;
    unsigned dist;
    if(ph_done || ph_bad)return -1;
    for(;;) {
        if(ph_bad)return -1;
        if(ph_copylen) {
            int c=ph_history[(unsigned)((ph_histpos-ph_copydist)&32767)];
            ph_copylen--;
            return ph_emit((unsigned)c);
        }
        if(ph_mode==4) {
            if(!ph_block()) {ph_bad=1;return -1;}
        }
        if(ph_mode==0) {
            if(ph_storedleft) {
                ph_storedleft--;
                return ph_emit(ph_bits(8));
            }
            ph_mode=4;
            if(ph_last){ph_done=1;return -1;}
            continue;
        }
        s=ph_symbol(&ph_lit);
        if(s<0)return -1;
        if(s<256)return ph_emit((unsigned)s);
        if(s==256) {
            ph_mode=4;
            if(ph_last){ph_done=1;return -1;}
            continue;
        }
        if(s<257 || s>285){ph_bad=1;return -1;}
        s-=257;
        ph_copylen=lbase[s]+ph_bits(lextra[s]);
        d=ph_symbol(&ph_dist);
        if(d<0 || d>=30){ph_bad=1;return -1;}
        dist=dbase[d]+ph_bits(dextra[d]);
        if(ph_bad || !dist || (unsigned long)dist>ph_total_out) {
            ph_bad=1;return -1;
        }
        ph_copydist=dist;
    }
}
static unsigned ph_sample(unsigned col,unsigned channel) {
    unsigned long base=(unsigned long)col*ph_channels;
    unsigned bytepos,bitshift,bits;
    if(ph_depth<8) {
        bytepos=(unsigned)((base*ph_depth)/8);
        bitshift=8-ph_depth-(unsigned)((base*ph_depth)%8);
        bits=(1U<<ph_depth)-1;
        return (ph_curr[bytepos]>>bitshift)&bits;
    }
    bytepos=(unsigned)(base*(ph_depth/8)+(unsigned long)channel*
                (ph_depth/8));
    return ph_curr[bytepos]; /* high byte for 16bpc PNG */
}
static unsigned ph_alpha(unsigned srcx,unsigned index,unsigned r,unsigned g,unsigned b) {
    if(ph_type==3) {
        if(index<ph_palette_count)return ph_palette[index][3];
        return 255;
    }
    if(ph_type==4)return ph_sample(srcx,1);
    if(ph_type==6)return ph_sample(srcx,3);
    if(ph_gray_transparent && ph_type==0 &&
       r==ph_trns_gray)return 0;
    if(ph_rgb_transparent && ph_type==2 &&
       r==ph_trns_r && g==ph_trns_g && b==ph_trns_b)return 0;
    return 255;
}
static unsigned ph_white(unsigned channel,unsigned a) {
    return (unsigned)(((unsigned long)channel*a+
                  (unsigned long)255*(255-a))/255UL);
}
static void ph_sample_row(unsigned row,unsigned *target_y) {
    unsigned x;
    while(*target_y<cv_codec_height &&
          (unsigned)((unsigned long)(*target_y)*ph_height/cv_codec_height)==row) {
        for(x=0;x<cv_codec_width;x++) {
            unsigned srcx=(unsigned)((unsigned long)x*ph_width/cv_codec_width);
            unsigned color=ph_sample(srcx,0);
            unsigned r,g,b,a;
            switch(ph_type) {
              case 0:
                r=g=b=color;
                if(ph_depth<8) {
                    r=g=b=(unsigned)((unsigned long)color*255/
                                          ((1U<<ph_depth)-1));
                }
                break;
              case 2:
                r=color;g=ph_sample(srcx,1);b=ph_sample(srcx,2);
                break;
              case 3:
                if(color>=ph_palette_count) {
                    r=g=b=0;
                } else {
                    r=ph_palette[color][0];
                    g=ph_palette[color][1];
                    b=ph_palette[color][2];
                }
                break;
              case 4:
                r=g=b=color;
                break;
              case 6:
                r=color;g=ph_sample(srcx,1);b=ph_sample(srcx,2);
                break;
              default: r=g=b=0;
            }
            a=ph_alpha(srcx,color,r,g,b);
            cv_codec_put(x,*target_y,ph_white(r,a),ph_white(g,a),ph_white(b,a));
        }
        (*target_y)++;
    }
}
static unsigned ph_paeth(unsigned a,unsigned b,unsigned c) {
    long p=(long)a+(long)b-(long)c;
    long da=p-a,db=p-b,dc=p-c;
    if(da<0)da=-da;
    if(db<0)db=-db;
    if(dc<0)dc=-dc;
    if(da<=db && da<=dc)return a;
    if(db<=dc)return b;
    return c;
}
static int ph_decode_rows(void) {
    unsigned row,x,filter,raw,a,b,c,reconstructed;
    unsigned outy=0;
    unsigned char __far *temp;
    int cmf,flg;
    cmf=ph_next_zbyte();
    flg=ph_next_zbyte();
    if(cmf<0 || flg<0 || (cmf&15)!=8 || (cmf>>4)>7 ||
        ((cmf<<8)|flg)%31!=0 || (flg&32)) return 0;
    ph_bitbuf=0;ph_bitcount=0;ph_mode=4;ph_bad=0;
    ph_copylen=0;ph_total_out=0;ph_histpos=0;ph_done=0;ph_last=0;
    for(row=0;row<ph_height;row++) {
        filter=ph_inflate();
        if(filter>4)return 0;
        for(x=0;x<ph_stride;x++) {
            raw=ph_inflate();
            if(raw>255)return 0;
            a=x>=ph_bpp ? ph_curr[x-ph_bpp]:0;
            b=ph_prev[x];
            c=x>=ph_bpp ? ph_prev[x-ph_bpp]:0;
            switch(filter) {
              case 0:reconstructed=raw;break;
              case 1:reconstructed=raw+a;break;
              case 2:reconstructed=raw+b;break;
              case 3:reconstructed=raw+((a+b)>>1);break;
              case 4:reconstructed=raw+ph_paeth(a,b,c);break;
              default:return 0;
            }
            ph_curr[x]=(unsigned char)(reconstructed&255);
        }
        ph_sample_row(row,&outy);
        temp=ph_curr;ph_curr=ph_prev;ph_prev=temp;
    }
    return outy==cv_codec_height && !ph_bad;
}
static void ph_cleanup(void) {
    if(ph_currhandle && ph_curr) {
        GlobalUnlock(ph_currhandle);ph_curr=(unsigned char __far *)0;
    }
    if(ph_prevhandle && ph_prev) {
        GlobalUnlock(ph_prevhandle);ph_prev=(unsigned char __far *)0;
    }
    if(ph_currhandle) {
        GlobalFree(ph_currhandle);ph_currhandle=(HGLOBAL)0;
    }
    if(ph_prevhandle) {
        GlobalFree(ph_prevhandle);ph_prevhandle=(HGLOBAL)0;
    }
    if(ph_history_handle) {
        if(ph_history) {
            GlobalUnlock(ph_history_handle);
            ph_history=(unsigned char __far *)0;
        }
        GlobalFree(ph_history_handle);
        ph_history_handle=(HGLOBAL)0;
    }
}
static int ph_alloc(void) {
    unsigned i;
    ph_history_handle=GlobalAlloc(GMEM_MOVEABLE,(DWORD)PNG_WINSIZE);
    ph_currhandle=GlobalAlloc(GMEM_MOVEABLE,(DWORD)ph_stride);
    ph_prevhandle=GlobalAlloc(GMEM_MOVEABLE,(DWORD)ph_stride);
    if(!ph_history_handle || !ph_currhandle || !ph_prevhandle)return 0;
    ph_history=(unsigned char __far *)GlobalLock(ph_history_handle);
    ph_curr=(unsigned char __far *)GlobalLock(ph_currhandle);
    ph_prev=(unsigned char __far *)GlobalLock(ph_prevhandle);
    if(!ph_history || !ph_curr || !ph_prev)return 0;
    for(i=0;i<ph_stride;i++)ph_prev[i]=0;
    return 1;
}
static int ph_init_png(void) {
    static const unsigned char sig[8]={137,80,78,71,13,10,26,10};
    unsigned i;
    unsigned long length;
    unsigned char name[5];
    int byte;
    ph_bad=0;
    ph_palette_count=0;
    ph_gray_transparent=0;
    ph_rgb_transparent=0;
    for(i=0;i<256;i++) {
        ph_palette[i][0]=ph_palette[i][1]=ph_palette[i][2]=0;
        ph_palette[i][3]=255;
    }
    for(i=0;i<8;i++) {
        byte=ph_filebyte();
        if(byte<0 || byte!=sig[i])return 0;
    }
    if(!ph_chunk(&length,name) || !ph_is(name,"IHDR") || length!=13)return 0;
    { unsigned long w=ph_be32(),h=ph_be32();
      if(w>8192UL || h>8192UL)return 0;
      ph_width=(unsigned)w;ph_height=(unsigned)h; }
    if(ph_bad || !ph_width || !ph_height || ph_width>8192 || ph_height>8192)
        return 0;
    ph_depth=(unsigned)ph_filebyte();
    ph_type=(unsigned)ph_filebyte();
    if(ph_filebyte()!=0 || ph_filebyte()!=0 || ph_filebyte()!=0)return 0;
    if(!ph_skip(4))return 0; /* CRC */
    switch(ph_type) {
      case 0:ph_channels=1;break;
      case 2:ph_channels=3;break;
      case 3:ph_channels=1;break;
      case 4:ph_channels=2;break;
      case 6:ph_channels=4;break;
      default:return 0;
    }
    if(ph_type==3) {
        if(ph_depth!=1 && ph_depth!=2 && ph_depth!=4 && ph_depth!=8)return 0;
    } else if(ph_type==0) {
        if(ph_depth!=1 && ph_depth!=2 && ph_depth!=4 &&
           ph_depth!=8 && ph_depth!=16)return 0;
    } else if(ph_depth!=8 && ph_depth!=16)return 0;
    ph_stride=(unsigned)(((unsigned long)ph_width*ph_channels*ph_depth+7)/8);
    ph_bpp=(unsigned)((ph_channels*ph_depth+7)/8);
    if(!ph_bpp)ph_bpp=1;
    if(!ph_stride || ph_stride>32768)return 0;
    for(;;) {
        if(!ph_chunk(&length,name))return 0;
        if(ph_is(name,"IDAT")) {
            if(ph_type==3 && !ph_palette_count)return 0;
            ph_idat_remaining=length;
            return 1;
        } else if(ph_is(name,"PLTE")) {
            unsigned j;
            if(length==0 || length>768 || length%3)return 0;
            ph_palette_count=(unsigned)(length/3);
            for(j=0;j<ph_palette_count;j++) {
                for(i=0;i<3;i++){
                    byte=ph_filebyte();
                    if(byte<0)return 0;
                    ph_palette[j][i]=(unsigned char)byte;
                }
            }
        } else if(ph_is(name,"tRNS")) {
            unsigned j;
            if(ph_type==3) {
                if(length>ph_palette_count)return 0;
                for(j=0;j<(unsigned)length;j++) {
                    byte=ph_filebyte();
                    if(byte<0)return 0;
                    ph_palette[j][3]=(unsigned char)byte;
                }
            } else if(ph_type==0) {
                if(length!=2)return 0;
                ph_trns_gray=ph_be16();ph_gray_transparent=1;
                if(ph_depth==16)ph_trns_gray>>=8;
            } else if(ph_type==2) {
                if(length!=6)return 0;
                ph_trns_r=ph_be16();ph_trns_g=ph_be16();
                ph_trns_b=ph_be16();
                if(ph_depth==16){
                    ph_trns_r>>=8;ph_trns_g>>=8;ph_trns_b>>=8;
                }
                ph_rgb_transparent=1;
            } else {
                if(!ph_skip(length))return 0;
            }
        } else if(ph_is(name,"IEND"))return 0;
        else {
            if(!ph_skip(length))return 0;
        }
        if(ph_bad || !ph_skip(4))return 0; /* chunk CRC */
    }
}
int cv_decode_png(HFILE file) {
    int ok=0;
    ph_file=file;
    ph_filepos=0;ph_filecount=0;
    ph_history_handle=ph_currhandle=ph_prevhandle=(HGLOBAL)0;
    ph_history=ph_curr=ph_prev=(unsigned char __far *)0;
    if(_llseek(file,0L,0)!=0L)return 0;
    if(ph_init_png() && cv_codec_begin(ph_width,ph_height) &&
       ph_alloc())ok=ph_decode_rows();
    ph_cleanup();
    if(!ok)cv_codec_release();
    return ok;
}
