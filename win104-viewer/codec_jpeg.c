/* picojpeg: public-domain Rich Geldreich 8/16-bit microcontroller
 * baseline JPEG decompression, using Windows 1.x KERNEL file I/O.
 * No stdio, malloc or DOS direct file calls.
 */
#include "codecs.h"
#include "picojpeg.h"

static HFILE jpeg_file;
static pjpeg_image_info_t jpeg_info;

static unsigned char jpeg_need(unsigned char *dest,unsigned char requested,
                               unsigned char *actual,void *context) {
    unsigned got;
    (void)context;
    got=_lread(jpeg_file,(void __huge *)dest,(UINT)requested);
    *actual=(unsigned char)(got>requested?0:got);
    return 0;
}
int cv_decode_jpeg(HFILE file) {
    unsigned char err;
    unsigned reduce=0;
    unsigned col,row,x,y,local_x,local_y;
    unsigned dleft,dright,dtop,dbottom,mcu_w,mcu_h;
    unsigned block_offset,pix_offset,block_w;
    unsigned long sx,sy;
    jpeg_file=file;
    if(_llseek(file,0L,0)!=0L)return 0;
    err=pjpeg_decode_init(&jpeg_info,jpeg_need,(void *)0,0);
    if(err || jpeg_info.m_width<1 || jpeg_info.m_height<1)return 0;
    if(jpeg_info.m_width>8192 || jpeg_info.m_height>8192)return 0;
    if(jpeg_info.m_width>512 || jpeg_info.m_height>512) {
        reduce=1;
        if(_llseek(file,0L,0)!=0L)return 0;
        err=pjpeg_decode_init(&jpeg_info,jpeg_need,(void *)0,1);
        if(err)return 0;
    }
    if(!cv_codec_begin((unsigned)jpeg_info.m_width,(unsigned)jpeg_info.m_height))
        return 0;
    mcu_w=(unsigned)jpeg_info.m_MCUWidth;
    mcu_h=(unsigned)jpeg_info.m_MCUHeight;
    block_w=mcu_w/8;
    for(row=0;row<(unsigned)jpeg_info.m_MCUSPerCol;row++){
        for(col=0;col<(unsigned)jpeg_info.m_MCUSPerRow;col++){
            err=pjpeg_decode_mcu();
            if(err){cv_codec_release();return 0;}
            /* MCU decode into the original, full-res source coordinates.
               Only downsampled destination samples are copied into EGA. */
            dleft=(unsigned)((unsigned long)col*mcu_w*
                       cv_codec_width/cv_codec_source_width);
            dright=(unsigned)((((unsigned long)(col+1)*mcu_w*
                       cv_codec_width)+cv_codec_source_width-1)/
                       cv_codec_source_width);
            dtop=(unsigned)((unsigned long)row*mcu_h*
                       cv_codec_height/cv_codec_source_height);
            dbottom=(unsigned)((((unsigned long)(row+1)*mcu_h*
                       cv_codec_height)+cv_codec_source_height-1)/
                       cv_codec_source_height);
            if(dright>cv_codec_width)dright=cv_codec_width;
            if(dbottom>cv_codec_height)dbottom=cv_codec_height;
            for(y=dtop;y<dbottom;y++) {
                sy=(unsigned long)y*cv_codec_source_height/cv_codec_height;
                if(sy<(unsigned long)row*mcu_h ||
                   sy>=(unsigned long)(row+1)*mcu_h)continue;
                local_y=(unsigned)(sy-(unsigned long)row*mcu_h);
                for(x=dleft;x<dright;x++) {
                    sx=(unsigned long)x*cv_codec_source_width/cv_codec_width;
                    if(sx<(unsigned long)col*mcu_w ||
                       sx>=(unsigned long)(col+1)*mcu_w)continue;
                    local_x=(unsigned)(sx-(unsigned long)col*mcu_w);
                    block_offset=(local_y/8)*block_w+(local_x/8);
                    pix_offset=block_offset*64;
                    if(!reduce)pix_offset+=(local_y%8)*8+(local_x%8);
                    if(jpeg_info.m_comps==1) {
                        unsigned g=jpeg_info.m_pMCUBufR[pix_offset];
                        cv_codec_put(x,y,g,g,g);
                    } else {
                        cv_codec_put(x,y,
                            jpeg_info.m_pMCUBufR[pix_offset],
                            jpeg_info.m_pMCUBufG[pix_offset],
                            jpeg_info.m_pMCUBufB[pix_offset]);
                    }
                }
            }
        }
    }
    return 1;
}
