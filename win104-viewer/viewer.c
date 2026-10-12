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
static HCURSOR app_cursor;
static char app_class[] = "Chrono104";
static unsigned paint_count = 0;
static HGLOBAL image_handle;
static int fit_image = 1;
static char status_text[128] = "Enter a CV1 file path and select Open";
static char image_path[128];
/* Static DS storage is mandatory with -zu/-zw: stack locals reside in SS,
 * but Win16 C runtime calls like fopen/fread/memset take near DS pointers. */
static char path_copy[128];
static unsigned char cv_header[8];
static char path_request[128];
static char arg_path[128];
static WNDCLASS window_class;
static RECT image_area;

U8 __far *image_pixels = 0;
unsigned image_w = 0, image_h = 0, actual_w = 0, actual_h = 0;
char format_name[18] = "CV1 - EGA";
const unsigned char ega_rgb[16][3] = {
 {0,0,0}, {0,0,170}, {0,170,0}, {0,170,170},
 {170,0,0}, {170,0,170}, {170,85,0}, {170,170,170},
 {85,85,85}, {85,85,255}, {85,255,85}, {85,255,255},
 {255,85,85}, {255,85,255}, {255,255,85}, {255,255,255}
};

long FAR PASCAL ViewWndProc(HWND h, unsigned msg, WORD wParam, LONG lParam);

/* DOS-side startup trace, useful even if USER.EXE locks up. */
static void trace_boot(const char *stage, int reset) {
    FILE *logfile=fopen("CHRONO.LOG",reset ? "w" : "a");
    if(logfile) {
        fputs(stage,logfile);
        fputc('\n',logfile);
        fclose(logfile);
    }
}
static void set_status(const char *msg) {
    /* Only short fixed strings or bounded edit-box contents reach here. */
    lstrcpy(status_text, msg);
    if (main_window)
        InvalidateRect(main_window, (LPRECT)0, TRUE);
}
static int load_cv1(const char *name) {
    FILE *f;
    unsigned w, h;
    unsigned long count, i;
    HGLOBAL next_handle;
    U8 __far *new_pixels;
    int c;

    /* The edit box limits names to at most 127 chars. */
    if (!name || !*name) {
        set_status("Type an image file path first");
        return 0;
    }
    lstrcpy(path_copy,name);
    f=fopen(path_copy,"rb");
    if(!f) {
        set_status("Unable to open this file");
        return 0;
    }
    if(fread(cv_header,1,8,f)!=8 ||
       cv_header[0]!='C' || cv_header[1]!='V' || cv_header[2]!='1' || cv_header[3]!=26) {
        fclose(f);
        set_status("Invalid CV1 file - use converter first");
        return 0;
    }
    w=(unsigned)cv_header[4] | ((unsigned)cv_header[5]<<8);
    h=(unsigned)cv_header[6] | ((unsigned)cv_header[7]<<8);
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
    lstrcpy(image_path,path_copy);
    set_status("CV1 image loaded - View > Fit controls zoom");
    return 1;
}
static void open_edit_path(void) {
    if(!path_box) {
        set_status("Image file path control unavailable");
        return;
    }
    GetWindowText(path_box,path_request,sizeof(path_request));
    if(path_request[0])
        load_cv1(path_request);
    else
        set_status("Type a path such as C:\\IMAGE.CV1");
}
static void toggle_fit(void) {
    fit_image=!fit_image;
    if(main_window)InvalidateRect(main_window,(LPRECT)0,TRUE);
}
static void show_about(HWND h) {
    (void)h;
    set_status("ChronoView for Windows 1.04 - vintage EGA mode");
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
long FAR PASCAL ViewWndProc(HWND h, unsigned msg, WORD wParam, LONG lParam) {
    PAINTSTRUCT ps;
    HDC dc;
    switch(msg) {
      case WM_CREATE:
        main_window=h;
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
        return DefWindowProc(h,msg,wParam,lParam);
      case WM_MOUSEMOVE:
        if(app_cursor) SetCursor(app_cursor);
        return 0L;
      case WM_KEYDOWN:
        if(wParam=='O' || wParam==VK_F3) { open_edit_path(); return 0L; }
        if(wParam=='F' || wParam==VK_F4) { toggle_fit(); return 0L; }
        break;
      case WM_PAINT:
        if(!paint_count) {
            paint_count=1;
            trace_boot("STEP 08 WM_PAINT entered",0);
        }
        dc=BeginPaint(h,&ps);
        GetClientRect(h,&image_area);
        draw_picture(dc,&image_area);
        TextOut(dc,5,(short)(image_area.bottom-16),
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
    MSG message;
    HWND window;
    HWND open_button;
    HWND fit_button;
    HWND about_button;
    int got_message;
    (void)show;
    instance=inst;
    trace_boot("STEP 01 WinMain reached",1);
    app_cursor=LoadCursor((HINSTANCE)0,IDC_ARROW);
    trace_boot(app_cursor ? "STEP 02 cursor loaded" : "STEP 02 cursor missing",0);
    if(!prev) {
        memset(&window_class,0,sizeof(window_class));
        window_class.style=CS_HREDRAW|CS_VREDRAW;
        window_class.lpfnWndProc=(WNDPROC)ViewWndProc;
        window_class.hInstance=inst;
        window_class.hIcon=LoadIcon(inst,MAKEINTRESOURCE(101));
        if(!window_class.hIcon)
            window_class.hIcon=LoadIcon((HINSTANCE)0,IDI_APPLICATION);
        window_class.hCursor=app_cursor;
        window_class.hbrBackground=(HBRUSH)GetStockObject(WHITE_BRUSH);
        window_class.lpszMenuName=(LPSTR)0;
        window_class.lpszClassName=app_class;
        trace_boot(window_class.hIcon ? "STEP 03 app icon loaded" : "STEP 03 app icon unavailable",0);
        if(!RegisterClass(&window_class)) {
            trace_boot("ERROR 04 RegisterClass failed (no MessageBox)",0);
            return 1;
        }
        trace_boot("STEP 04 RegisterClass succeeded",0);
    }
    window=CreateWindow(app_class,"ChronoView",
         WS_TILEDWINDOW,0,0,0,0,
         (HWND)0,(HMENU)0,inst,(LPSTR)0);
    if(!window) {
        trace_boot("ERROR 05 CreateWindow failed (no MessageBox)",0);
        return 2;
    }
    main_window=window;
    trace_boot("STEP 05 main window created",0);

    /* Windows 1.04 can behave unpredictably if child controls are created
     * reentrantly inside the parent window's WM_CREATE. Do this afterward. */
    path_box=CreateWindow("EDIT","C:\\IMAGE.CV1",
         WS_CHILD|WS_VISIBLE|WS_BORDER|ES_AUTOHSCROLL,
         5,5,205,21,window,(HMENU)ID_PATH,inst,(LPSTR)0);
    open_button=CreateWindow("BUTTON","Open",
         WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
         216,5,48,21,window,(HMENU)ID_OPEN,inst,(LPSTR)0);
    fit_button=CreateWindow("BUTTON","Fit",
         WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
         270,5,44,21,window,(HMENU)ID_FIT,inst,(LPSTR)0);
    about_button=CreateWindow("BUTTON","About",
         WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
         320,5,52,21,window,(HMENU)ID_ABOUT,inst,(LPSTR)0);
    trace_boot(path_box && open_button && fit_button && about_button ?
        "STEP 06 controls created" : "STEP 06 some controls unavailable",0);

    /* Never pass Win3.x nCmdShow values. Windows 1.x uses OPENWINDOW=1. */
    ShowWindow(window,1);
    trace_boot("STEP 07 ShowWindow completed, entering event loop",0);
    /* UpdateWindow() can cause a synchronous WM_PAINT with a premature DS
     * in old 16-bit Windows. Let GetMessage drive the first repaint. */
    InvalidateRect(window,(LPRECT)0,TRUE);
    if(cmd && *cmd) {
        unsigned j=0;
        const char __far *start=cmd;
        if(*start=='"')start++;
        while(*start && *start!='"' && j<127) arg_path[j++]=*start++;
        arg_path[j]=0;
        if(path_box)SetWindowText(path_box,arg_path);
        if(arg_path[0])load_cv1(arg_path);
    }
    for(;;) {
        got_message=GetMessage(&message,(HWND)0,0,0);
        if(!got_message)break;
        if(got_message<0)break;
        TranslateMessage(&message);
        DispatchMessage(&message);
    }
    trace_boot("STEP 09 event loop stopped",0);
    return (int)message.wParam;
}
