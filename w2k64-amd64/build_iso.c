#define _XOPEN_SOURCE 700
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <limits.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <ftw.h>
#include <archive.h>
#include <archive_entry.h>
static struct archive *writer;
static const char *root;
static size_t rootlen;
static int count=0;
static int failures=0;
static unsigned char buf[1024*1024];
static int add(const char *full,const struct stat *st,int type,struct FTW *ftw){
 (void)type;(void)ftw;
 const char *rel=full+rootlen;while(*rel=='/')rel++;
 if(!*rel)return 0;
 struct archive_entry *e=archive_entry_new();
 archive_entry_set_pathname(e,rel);
 archive_entry_copy_stat(e,st);
 if(S_ISDIR(st->st_mode))archive_entry_set_size(e,0);
 int rc=archive_write_header(writer,e);
 if(rc < ARCHIVE_WARN){fprintf(stderr,"header error %s: %s\n",rel,archive_error_string(writer));failures++;}
 else if(S_ISREG(st->st_mode)){
  int fd=open(full,O_RDONLY);
  if(fd<0){perror(full);failures++;}
  else {
   ssize_t n;
   while((n=read(fd,buf,sizeof(buf)))>0){
    ssize_t written=archive_write_data(writer,buf,n);
    if(written<0 || written!=n){fprintf(stderr,"write error %s: %s\n",rel,archive_error_string(writer));failures++;break;}
   }
   close(fd);
  }
 }
 archive_entry_free(e);count++;
 if(count%1500==0)fprintf(stderr,"adding: %d entries\n",count);
 return failures?1:0;
}
static void opt(const char *key, const char *value) {
 int rc=archive_write_set_format_option(writer,"iso9660",key,value);
 if(rc!=ARCHIVE_OK){fprintf(stderr,"ISO option error %s=%s: %s (%d)\n",key,value ? value : "(null)",archive_error_string(writer),rc);exit(2);}
}
int main(int argc,char**argv){
 if(argc<3){fprintf(stderr,"usage: build_iso ISO_ROOT OUTPUT.iso\n");return 2;}
 root=argv[1];rootlen=strlen(root);
 writer=archive_write_new();if(archive_write_set_format_iso9660(writer)!=ARCHIVE_OK)return 3;
 opt("joliet","long");opt("rockridge",NULL);opt("allow-vernum",NULL);opt("volume-id","AX2PXFPP_EN");
 opt("iso-level","3");
 opt("boot","W2K64_BOOT.IMG"); opt("boot-type","no-emulation"); opt("boot-load-size","4");
 opt("boot-catalog","BOOT.CAT");
 if(archive_write_open_filename(writer,argv[2])!=ARCHIVE_OK){fprintf(stderr,"open err: %s\n",archive_error_string(writer));return 4;}
 int code=nftw(root,add,32,FTW_PHYS);
 if(code!=0)fprintf(stderr,"walk errors %d\n",failures);
 int rc=archive_write_close(writer);
 if(rc!=ARCHIVE_OK)fprintf(stderr,"close err: %s\n",archive_error_string(writer));
 archive_write_free(writer);
 fprintf(stderr,"ISO completed: %d entries, status %d, failures %d\n",count,rc,failures);
 return (code||failures||rc!=ARCHIVE_OK)?5:0;
}
