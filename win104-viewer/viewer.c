/* ChronoView for Microsoft Windows 1.04.
 * Compile for 8086 real mode, WINVER=0x0100, with -zu -zw.
 * Link with 'system windows1', not the default 'windows' target.
 * WndProc MUST be exported and use FAR PASCAL __loadds.
 * No Windows 2.x/3.x APIs are intentionally imported.
 *
 * Images use the compact 16-colour indexed CV1 format, converted from
 * PNG/JPEG/WebP in a modern browser. This is not native WebP decoding.
 */
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "viewer.h"

#define ID_OPEN     201
#define ID_FIT      202
#define ID_ABOUT    203
#define ID_EXIT     204
#define ID_PATH     205
#define MAX_PIXELS 60000UL
#define FILE_W_MAX 640
#define FILE_H_MAX 480

static HINSTANCE instance;
static HWND main_window, path_box;
static HGLOBAL image_handle;
static int fit_image = 1;
static char status_text[128] = "Enter a CV1 file path and select Open";
static char image_path[128];

U8 __far *image_pixels = 0;
unsigned image_w = 0, image_h = 0, actual_w = 0, actual_h = 0;
char format_name[18] = "CV1 - EGA";
const unsigned char ega_rgb[16][3] = {
 {0,0,0}, {0,0,170}, {0,170,0}, {0,170,170},
 {170,0,0}, {170,0,170}, {170,85,0}, {170,170,170},
 {85,85,85}, {85,85,255}, {85,255,85}, {85,255,255},
 {255,85,85}, {255,85,255}, {255,255,85}, {255,255,255}
};

long FAR PASCAL __loadds ViewWndProc(HWND h, unsigned msg, WORD wParam, LONG lParam);

static void set_status(const char *msg) {
    /* Only short fixed strings or bounded edit-box contents reach here. */
    lstrcpy(status_text, msg);
    if (main_window)
        InvalidateRect(main_window, (LPRECT)0, TRUE);
}
static int load_cv1(const char *name) {
    FILE *f;
    unsigned char hdr[8];
    unsigned w, h;
    unsigned long count, i;
    HGLOBAL next_handle;
    U8 __far *new_pixels;
    int c;
    char pathcopy[128];

    /* The edit box limits names to at most 127 chars. */
    if (!name || !*name) {
        set_status("Type an image file path first");
        return 0;
    }
    lstrcpy(pathcopy,name);
    f=fopen(pathcopy,"rb");
    if(!f) {
        set_status("Unable to open this file");
        return 0;
    }
    if(fread(hdr,1,8,f)!=8 ||
       hdr[0]!='C' || hdr[1]!='V' || hdr[2]!='1' || hdr[3]!=26) {
        fclose(f);
        set_status("Invalid CV1 file - use converter first");
        return 0;
    }
    w=(unsigned)hdr[4] | ((unsigned)hdr[5]<<8);
    h=(unsigned)hdr[6] | ((unsigned)hdr[7]<<8);
    count=(unsigned long)w*(unsigned long)h;
    if(!w || !h || w>FILE_W_MAX || h>FILE_H_MAX || count>MAX_PIXELS) {
        fclose(f);
        set_status("Image too large; reconvert below 60000 pixels");
        return 0;
    }
    next_handle=GlobalAlloc(GMEM_MOVEABLE, (DWORD)count);
    if(!next_handle) {
        fclose(f);
        set_status("Insufficient free Windows memory");
        return 0;
    }
    new_pixels=(U8 __far *)GlobalLock(next_handle);
    if(!new_pixels) {
        GlobalFree(next_handle);
        fclose(f);
        set_status("Unable to lock image memory");
        return 0;
    }
    for(i=0;i<count;++i) {
        c=fgetc(f);
        if(c<0 || c>15) break;
        new_pixels[(unsigned)i]=(U8)c;
    }
    fclose(f);
    if(i!=count) {
        GlobalUnlock(next_handle);
        GlobalFree(next_handle);
        set_status("Truncated or corrupt image file");
        return 0;
    }
    if(image_pixels) {
        GlobalUnlock(image_handle);
        GlobalFree(image_handle);
    }
    image_pixels=new_pixels;
    image_handle=next_handle;
    image_w=w;
    image_h=h;
    actual_w=w;
    actual_h=h;
    lstrcpy(image_path,pathcopy);
    set_status("CV1 image loaded - View > Fit controls zoom");
    return 1;
}
static void open_edit_path(void) {
    char path[128];
    if(!path_box) {
        set_status("Image file path control unavailable");
        return;
    }
    GetWindowText(path_box,path,sizeof(path));
    if(path[0])
        load_cv1(path);
    else
        set_status("Type a path such as C:\\IMAGE.CV1");
}
static void toggle_fit(void) {
    fit_image=!fit_image;
    if(main_window)InvalidateRect(main_window,(LPRECT)0,TRUE);
}
static void show_about(HWND h) {
    MessageBox(h,
      "ChronoView for Microsoft Windows 1.04\n"
      "16-colour EGA CV1 image viewer\n"
      "Convert modern images with convert.html on your phone.",
      "About ChronoView",MB_OK);
}
static void draw_picture(HDC dc, const RECT *rect) {
    HBRUSH colors[16];
    HBRUSH prior;
    unsigned vieww,viewh,renderw,renderh;
    unsigned row,col,sourcex,sourcey;
    unsigned previous,start,shade;
    unsigned i;
    short left,top;

    if(!image_pixels || !image_w || !image_h)return;
    vieww=(unsigned)(rect->right-rect->left);
    if(rect->bottom-rect->top <= 58)return;
    viewh=(unsigned)(rect->bottom-rect->top-58);
    if(vieww < 1 || viewh < 1)return;

    if(fit_image) {
        if((unsigned long)vieww*image_h < (unsigned long)viewh*image_w) {
            renderw=vieww;
            renderh=(unsigned)((unsigned long)vieww*image_h/image_w);
        } else {
            renderh=viewh;
            renderw=(unsigned)((unsigned long)viewh*image_w/image_h);
        }
    } else {
        renderw=image_w;
        renderh=image_h;
    }
    if(!renderw || !renderh)return;
    left=(short)(((int)vieww-(int)renderw)/2);
    top=(short)(31+((int)viewh-(int)renderh)/2);

    for(i=0;i<16;i++) {
        colors[i]=CreateSolidBrush(RGB(ega_rgb[i][0],
                ega_rgb[i][1],ega_rgb[i][2]));
    }
    for(row=0;row<renderh;row++) {
        sourcey=(unsigned)((unsigned long)row*image_h/renderh);
        previous=65535U;
        start=0;
        for(col=0;col<=renderw;col++) {
            shade=65535U;
            if(col<renderw) {
                sourcex=(unsigned)((unsigned long)col*image_w/renderw);
                shade=image_pixels[(unsigned)((unsigned long)sourcey*image_w+sourcex)];
            }
            if(shade!=previous) {
                if(col>start && previous<16 && colors[previous]) {
                    prior=(HBRUSH)SelectObject(dc,colors[previous]);
                    PatBlt(dc,(short)(left+start),(short)(top+row),
                           (short)(col-start),1,PATCOPY);
                    SelectObject(dc,prior);
                }
                start=col;
                previous=shade;
            }
        }
    }
    for(i=0;i<16;i++) if(colors[i]) DeleteObject(colors[i]);
}
long FAR PASCAL __loadds ViewWndProc(HWND h, unsigned msg, WORD wParam, LONG lParam) {
    PAINTSTRUCT ps;
    RECT bounds;
    HDC dc;
    switch(msg) {
      case WM_CREATE:
        main_window=h;
        path_box=CreateWindow("EDIT","C:\\IMAGE.CV1",
          WS_CHILD|WS_VISIBLE|WS_BORDER|ES_AUTOHSCROLL,
          5,5,280,21,h,(HMENU)ID_PATH,instance,(LPSTR)0);
        CreateWindow("BUTTON","Open", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
          291,5,51,21,h,(HMENU)ID_OPEN,instance,(LPSTR)0);
        return 0L;
      case WM_COMMAND:
        switch(wParam) {
          case ID_OPEN: open_edit_path(); return 0L;
          case ID_FIT: toggle_fit(); return 0L;
          case ID_ABOUT: show_about(h); return 0L;
          case ID_EXIT: DestroyWindow(h); return 0L;
        }
        break;
      case WM_SYSCOMMAND:
        switch(wParam) {
          case ID_FIT: toggle_fit(); return 0L;
          case ID_ABOUT: show_about(h); return 0L;
        }
        break;
      case WM_PAINT:
        dc=BeginPaint(h,&ps);
        GetClientRect(h,&bounds);
        draw_picture(dc,&bounds);
        TextOut(dc,5,(short)(bounds.bottom-16),
                status_text,lstrlen(status_text));
        EndPaint(h,&ps);
        return 0L;
      case WM_DESTROY:
        if(image_pixels) {
            GlobalUnlock(image_handle);
            GlobalFree(image_handle);
            image_pixels=0;
        }
        PostQuitMessage(0);
        return 0L;
    }
    return DefWindowProc(h,msg,wParam,lParam);
}
int PASCAL WinMain(HINSTANCE inst,HINSTANCE prev,LPSTR cmd,int show) {
    WNDCLASS cls;
    MSG message;
    HWND window;
    HMENU system_menu;
    char path[128];
    FARPROC callback;
    instance=inst;
    callback=MakeProcInstance((FARPROC)ViewWndProc,inst);
    if(!callback) return 0;
    if(!prev) {
        memset(&cls,0,sizeof(cls));
        cls.style=CS_HREDRAW|CS_VREDRAW;
        cls.lpfnWndProc=(WNDPROC)callback;
        cls.hInstance=inst;
        cls.hIcon=LoadIcon(0,IDI_APPLICATION);
        cls.hCursor=LoadCursor(0,IDC_ARROW);
        cls.hbrBackground=(HBRUSH)GetStockObject(WHITE_BRUSH);
        cls.lpszClassName="Chrono104";
        if(!RegisterClass(&cls)) {
            MessageBox(0,"Unable to register window class",
                       "ChronoView error",MB_OK);
            return 0;
        }
    }
    window=CreateWindow("Chrono104","ChronoView",
         WS_TILEDWINDOW,0,0,480,300,
         (HWND)0,(HMENU)0,inst,(LPSTR)0);
    if(!window) {
        MessageBox(0,"Unable to create a tiled window",
                   "ChronoView error",MB_OK);
        return 0;
    }
    main_window=window;
    system_menu=GetSystemMenu(window,FALSE);
    if(system_menu) {
        ChangeMenu(system_menu,0,(LPSTR)0,0,MF_APPEND|MF_SEPARATOR);
        ChangeMenu(system_menu,0,"Fit / Actual size",ID_FIT,
                   MF_APPEND|MF_STRING);
        ChangeMenu(system_menu,0,"About ChronoView",ID_ABOUT,
                   MF_APPEND|MF_STRING);
    }
    ShowWindow(window,show ? show : 1); /* Windows 1.x uses SHOW_OPENWINDOW=1 */
    UpdateWindow(window);
    if(cmd && *cmd) {
        /* Trim opening quote, if any. No Win3-only lstrcpyn import. */
        unsigned j=0;
        const char *start=cmd;
        if(*start=='"')start++;
        while(*start && *start!='"' && j<127) path[j++]=*start++;
        path[j]=0;
        if(path_box)SetWindowText(path_box,path);
        if(path[0])load_cv1(path);
    }
    while(GetMessage(&message,0,0,0)) {
        TranslateMessage(&message);
        DispatchMessage(&message);
    }
    /* Do not FreeProcInstance: Windows 1.0's lifetime is process-scoped. */
    return (int)message.wParam;
}
