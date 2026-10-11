/* ChronoView - native Win16 image viewer for Windows 1.x.
 * CV1 is a 4-bit indexed image with a fixed 16-colour EGA palette.
 * Modern PNG/JPEG/WebP images are converted with convert.html.
 */
#include "viewer.h"
#include <stdlib.h>
#include <string.h>
#define IDM_OPEN 101
#define IDM_FIT 102
#define IDM_ABOUT 103
#define IDM_EXIT 104
#define MAXW 640
#define MAXH 480
static HWND mainwnd;
static HGLOBAL image_handle=0;
static HINSTANCE appinst;
static HMENU menubar, filemenu, viewmenu;
static char filename[128]="";
static char status[128]="File > Open to view a CV1 image";
static int fit=1;
U8 __far *image_pixels=0;
unsigned image_w=0,image_h=0,actual_w=0,actual_h=0;
char format_name[18]="CV1";
const unsigned char ega_rgb[16][3]={
 {0,0,0},{0,0,170},{0,170,0},{0,170,170},
 {170,0,0},{170,0,170},{170,85,0},{170,170,170},
 {85,85,85},{85,85,255},{85,255,85},{85,255,255},
 {255,85,85},{255,85,255},{255,255,85},{255,255,255}
};
static void say(const char *s) {
 lstrcpy(status,s);
 if(mainwnd) InvalidateRect(mainwnd,(LPRECT)0,TRUE);
}
static int load_cv1(const char *path) {
 FILE *f;
 char header[4];
 unsigned short w,h;
 unsigned long bytes, i;
 U8 __far *pix;
 HGLOBAL newhandle;
 int b;
 f=fopen(path,"rb");
 if(!f) {say("Could not open file");return 0;}
 if(fread(header,1,4,f)!=4 || memcmp(header,"CV1\032",4)!=0 ||
    fread(&w,2,1,f)!=1 || fread(&h,2,1,f)!=1) {
   fclose(f);say("Not a CV1 file. Convert PNG/JPEG/WebP first.");return 0;
 }
 if(!w||!h||w>MAXW||h>MAXH){fclose(f);say("Image dimensions exceed 640 x 480");return 0;}
 bytes=(unsigned long)w*(unsigned long)h;
 if(bytes>65520UL){fclose(f);say("Image exceeds single-segment memory. Use a smaller conversion.");return 0;}
 newhandle=GlobalAlloc(GMEM_MOVEABLE,(DWORD)bytes);
 pix=newhandle?(U8 __far *)GlobalLock(newhandle):0;
 if(!pix){fclose(f);say("Insufficient conventional memory");return 0;}
 for(i=0;i<bytes;i++) {
   b=fgetc(f);
   if(b==EOF || b>15) break;
   pix[(unsigned)i]=(U8)b;
 }
 fclose(f);
 if(i!=bytes){
   GlobalUnlock(newhandle);GlobalFree(newhandle);
   say("Corrupt/truncated CV1 image");return 0;
 }
 if(image_pixels){GlobalUnlock(image_handle);GlobalFree(image_handle);}
 image_handle=newhandle;
 image_pixels=pix;image_w=w;image_h=h;
 lstrcpy(filename,path);say(path);
 return 1;
}
static void draw_image(HDC dc, RECT *rc) {
 unsigned x,y,dx,dy,sw,sh;
 long a,b;
 HBRUSH brushes[16],old;
 RECT p;
 if(!image_pixels)return;
 for(x=0;x<16;x++)
   brushes[x]=CreateSolidBrush(RGB(ega_rgb[x][0],ega_rgb[x][1],ega_rgb[x][2]));
 sw=(unsigned)(rc->right-rc->left);
 sh=(unsigned)(rc->bottom-rc->top-18);
 if(fit) {
   a=(long)sw*image_h;
   b=(long)sh*image_w;
   if(a<b) {dx=sw;dy=(unsigned)((long)sw*image_h/image_w);}
   else {dy=sh;dx=(unsigned)((long)sh*image_w/image_h);}
 } else {dx=image_w;dy=image_h;}
 if(!dx)dx=1;if(!dy)dy=1;
 p.left=(short)((sw-dx)/2);p.top=(short)((sh-dy)/2);
 for(y=0;y<dy;y++) {
   unsigned sy=(unsigned)((unsigned long)y*image_h/dy);
   unsigned last=65535,start=0;
   for(x=0;x<=dx;x++) {
     unsigned col=65535;
     if(x<dx) {
       unsigned sx=(unsigned)((unsigned long)x*image_w/dx);
       col=image_pixels[(unsigned)((unsigned long)sy*image_w+sx)];
     }
     if(col!=last) {
       if(x && last<16) {
         old=(HBRUSH)SelectObject(dc,brushes[last]);
         PatBlt(dc,(short)(p.left+start),(short)(p.top+y),
                (short)(x-start),1,PATCOPY);
         SelectObject(dc,old);
       }
       start=x;last=col;
     }
   }
 }
 for(x=0;x<16;x++)DeleteObject(brushes[x]);
}
static void choose_file(HWND h) {
 /* Windows 1.x predates the common open-file dialog. Use an edit-control
    modal prompt via a separate top-level window when needed. */
 FARPROC proc;
 char path[128];
 int ret;
 lstrcpy(path,filename[0]?filename:"C:\\IMAGE.CV1");
 ret=MessageBox(h,"Place IMAGE.CV1 in C:\\ or use the current path.\nOpen C:\\IMAGE.CV1 now?","Open image",MB_OKCANCEL);
 if(ret==IDOK) load_cv1(path[0] && filename[0]?filename:"C:\\IMAGE.CV1");
}
long FAR PASCAL ViewWndProc(HWND h, unsigned msg, WORD wp, LONG lp){
 PAINTSTRUCT ps;
 RECT rc;
 HDC dc;
 switch(msg){
  case WM_CREATE:
   mainwnd=h; return 0L;
  case WM_COMMAND:
   switch(wp) {
    case IDM_OPEN: choose_file(h);return 0L;
    case IDM_FIT: fit=!fit;InvalidateRect(h,0,TRUE);return 0L;
    case IDM_ABOUT: MessageBox(h,"ChronoView 1.0\nNative Windows 1.x image viewer\nCV1 16-colour indexed format","About",MB_OK);return 0L;
    case IDM_EXIT: DestroyWindow(h);return 0L;
   }
   break;
  case WM_PAINT:
   dc=BeginPaint(h,&ps);GetClientRect(h,&rc);
   draw_image(dc,&rc);
   TextOut(dc,3,(short)(rc.bottom-15),status,lstrlen(status));
   EndPaint(h,&ps);return 0L;
  case WM_DESTROY:PostQuitMessage(0);return 0L;
 }
 return DefWindowProc(h,msg,wp,lp);
}
int PASCAL WinMain(HINSTANCE inst,HINSTANCE prev,LPSTR args,int show){
 WNDCLASS wc;
 MSG msg;
 HWND h;
 char cmd[128];
 appinst=inst;
 if(!prev) {
  memset(&wc,0,sizeof(wc));
  wc.style=CS_HREDRAW|CS_VREDRAW;
  wc.lpfnWndProc=(WNDPROC)ViewWndProc;
  wc.hInstance=inst;
  wc.hIcon=LoadIcon(0,IDI_APPLICATION);
  wc.hCursor=LoadCursor(0,IDC_ARROW);
  wc.hbrBackground=(HBRUSH)GetStockObject(WHITE_BRUSH);
  wc.lpszClassName="ChronoView104";
  if(!RegisterClass(&wc))return 0;
 }
 menubar=CreateMenu();
 filemenu=CreatePopupMenu();viewmenu=CreatePopupMenu();
 AppendMenu(filemenu,MF_STRING,IDM_OPEN,"&Open");
 AppendMenu(filemenu,MF_STRING,IDM_EXIT,"E&xit");
 AppendMenu(viewmenu,MF_STRING,IDM_FIT,"&Fit to window");
 AppendMenu(viewmenu,MF_STRING,IDM_ABOUT,"&About");
 AppendMenu(menubar,MF_POPUP,(WORD)filemenu,"&File");
 AppendMenu(menubar,MF_POPUP,(WORD)viewmenu,"&View");
 h=CreateWindow("ChronoView104","ChronoView - Windows 1.04",
  WS_OVERLAPPEDWINDOW,10,10,480,300,0,menubar,inst,0);
 if(!h)return 0;
 mainwnd=h;
 ShowWindow(h,show);
 UpdateWindow(h);
 if(args && *args) {
  lstrcpyn(cmd,args,128);
  if(cmd[0]=='"') {
   char *p=strchr(cmd+1,'"');
   if(p){*p=0;load_cv1(cmd+1);}
  } else load_cv1(cmd);
 }
 while(GetMessage(&msg,0,0,0)) {TranslateMessage(&msg);DispatchMessage(&msg);}
 return (int)msg.wParam;
}
