/* Run native PNG and JPEG codecs against real files in Linux CI.
 * The implementation itself is identical to the Win16 source; only
 * KERNEL._lread/_llseek and GlobalAlloc are mapped to POSIX/malloc.
 */
#include "codecs.h"
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
extern unsigned cv_png_debug_stage,cv_png_debug_row;
extern unsigned long cv_png_debug_output;
static int check(char *path,char *format,int expected) {
    int f=open(path,O_RDONLY);
    unsigned result=0;
    unsigned long sum=0;
    unsigned i;
    if(f<0){perror(path);return 1;}
    if(!strcmp(format,"png"))result=(unsigned)cv_decode_png(f);
    else if(!strcmp(format,"jpg"))result=(unsigned)cv_decode_jpeg(f);
    close(f);
    if(!result) {
        printf("%s: DECODE FAILED PNGstage=%u row=%u bytes=%lu\n",path,cv_png_debug_stage,cv_png_debug_row,cv_png_debug_output);
        return 1;
    }
    if(!cv_codec_pixels || !cv_codec_width || !cv_codec_height){
        puts("Output not allocated");return 1;
    }
    for(i=0;i<cv_codec_width*cv_codec_height;i++) {
        unsigned pix=cv_codec_pixels[i];
        if(pix>15){puts("Invalid EGA palette index");return 1;}
        sum+=pix;
    }
    printf("%s: %u x %u, sum=%lu\n",path,
        cv_codec_width,cv_codec_height,sum);
    if(expected>=0 && sum!=(unsigned long)expected) {
        puts("Unexpected EGA palette result");
        return 1;
    }
    cv_codec_release();
    return 0;
}
int main(int argc,char **argv) {
    int i,failed=0;
    if((argc-1)%3){puts("Expected: format filename palette-sum [... ]");return 2;}
    for(i=1;i<argc;i+=3)
        failed|=check(argv[i+1],argv[i],atoi(argv[i+2]));
    return failed;
}
