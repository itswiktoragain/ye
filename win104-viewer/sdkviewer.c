/* ChronoView SAFE - Windows 1.04, 8086 real-mode, API as in Win1 SDK.
 *
 * The original modern-style implementation crashed Windows 1.04 during
 * window movement. This rewrite avoids __loadds, custom child controls,
 * extra window classes, DOS file I/O during startup, and discardable code.
 *
 * Supported natively: indexed 16-colour CV1 images.
 * PNG/JPEG/WebP use the bundled converter.html on the host machine.
 */
#include <windows.h>
#include <stdio.h>
#include <string.h>

#define PIXEL_LIMIT 60000UL
#define PATH_CAP 100

/* Writable class/title strings in the default data segment, not code. */
static char app_class[18]="CVIEW104";
static char app_title[22]="ChronoView";
static char command_help[64]="O Open   P Path   F Fit   Q Quit";
static char file_path[PATH_CAP]="C:\\IMAGE.CV1";
static char app_status[88]="Press O to open image. Press P to change path.";
static char text_label[18]="File:";
static char text_edit[25]="Editing path: Enter";
static int path_length=12;
static int editing=0;
static int fit=1;
static HANDLE pixels_handle=0;
static unsigned char __far *pixels=0;
static unsigned width=0,height=0;

static const unsigned char palette[16][3]={
 {0,0,0},{0,0,170},{0,170,0},{0,170,170},
 {170,0,0},{170,0,170},{170,85,0},{170,170,170},
 {85,85,85},{85,85,255},{85,255,85},{85,255,255},
 {255,85,85},{255,85,255},{255,255,85},{255,255,255}
};

static HWND main_window=0;
static unsigned char cv_header[8];
static char open_path[PATH_CAP];
static RECT paint_rect;
/* No new C library calls that take pointers into SS: use data statics. */

long FAR PASCAL View104Proc(HWND h,unsigned msg,WORD wp,LONG lp);

static void set_status(const char *message) {
    unsigned i=0;
    while(message[i] && i<sizeof(app_status)-1) {
        app_status[i]=message[i];
        ++i;
    }
    app_status[i]=0;
    if(main_window) InvalidateRect(main_window,(LPRECT)0,TRUE);
}
static void invalidate_image(void) {
    if(main_window) InvalidateRect(main_window,(LPRECT)0,TRUE);
}
static void open_image(void) {
    FILE *input;
    unsigned w,h;
    unsigned long need,j;
    unsigned char __far *next_pixels;
    HANDLE next_handle;
    int value;

    /* No fopen until an explicit user action: never perform DOS I/O while
     * the program is registering or creating its primary Win1 window. */
    if(!file_path[0]) {
        set_status("Enter a file path first");
        return;
    }
    {
        unsigned i=0;
        while(file_path[i] && i<PATH_CAP-1) {
            open_path[i]=file_path[i];i++;
        }
        open_path[i]=0;
    }
    input=fopen(open_path,"rb");
    if(!input) {
        set_status("Unable to open image file");
        return;
    }
    if(fread(cv_header,1,8,input)!=8 ||
       cv_header[0]!='C' ||cv_header[1]!='V' ||
       cv_header[2]!='1' ||cv_header[3]!=26) {
        fclose(input);
        set_status("Not CV1. Convert PNG/JPEG/WebP first.");
        return;
    }
    w=(unsigned)cv_header[4]+((unsigned)cv_header[5]<<8);
    h=(unsigned)cv_header[6]+((unsigned)cv_header[7]<<8);
    need=(unsigned long)w*(unsigned long)h;
    if(!w || !h ||w>640 || h>480 || need>PIXEL_LIMIT) {
        fclose(input);
        set_status("Image too large (60000 pixels max)");
        return;
    }
    next_handle=GlobalAlloc(GMEM_MOVEABLE,(DWORD)need);
    if(!next_handle) {
        fclose(input);
        set_status("Insufficient Windows 1.04 memory");
        return;
    }
    next_pixels=(unsigned char __far *)GlobalLock(next_handle);
    if(!next_pixels) {
        GlobalFree(next_handle);
        fclose(input);
        set_status("Unable to lock image memory");
        return;
    }
    for(j=0;j<need;j++){
        value=fgetc(input);
        if(value<0 || value>15)break;
        next_pixels[(unsigned)j]=(unsigned char)value;
    }
    fclose(input);
    if(j!=need) {
        GlobalUnlock(next_handle);
        GlobalFree(next_handle);
        set_status("Corrupt / truncated CV1 image");
        return;
    }
    if(pixels) {
        GlobalUnlock(pixels_handle);
        GlobalFree(pixels_handle);
    }
    pixels_handle=next_handle;
    pixels=next_pixels;
    width=w;height=h;
    set_status("Loaded CV1 image. F toggles actual size.");
}
static void draw_image(HDC dc,RECT __far *area) {
    HBRUSH brushes[16];
    HBRUSH old;
    unsigned screen_w,screen_h,draw_w,draw_h;
    unsigned x,y,sx,sy,run_start,previous,index;
    short off_x,off_y;
    if(!pixels || !width || !height) return;
    screen_w=(unsigned)(area->right-area->left);
    if(area->bottom-area->top<=52)return;
    screen_h=(unsigned)(area->bottom-area->top-52);
    if(!screen_w || !screen_h)return;
    if(fit){
        if((unsigned long)screen_w*height <=
           (unsigned long)screen_h*width) {
            draw_w=screen_w;
            draw_h=(unsigned)((unsigned long)draw_w*height/width);
        } else {
            draw_h=screen_h;
            draw_w=(unsigned)((unsigned long)draw_h*width/height);
        }
    } else {
        draw_w=width;draw_h=height;
    }
    if(!draw_w || !draw_h)return;
    off_x=(short)(((int)screen_w-(int)draw_w)/2);
    off_y=(short)(50+((int)screen_h-(int)draw_h)/2);
    for(index=0;index<16;index++)
        brushes[index]=CreateSolidBrush(RGB(palette[index][0],
                     palette[index][1],palette[index][2]));
    for(y=0;y<draw_h;y++){
        sy=(unsigned)((unsigned long)y*height/draw_h);
        previous=65535;
        run_start=0;
        for(x=0;x<=draw_w;x++){
            index=65535;
            if(x<draw_w){
                sx=(unsigned)((unsigned long)x*width/draw_w);
                index=pixels[(unsigned)((unsigned long)sy*width+sx)];
            }
            if(index!=previous){
                if(previous<16 && x>run_start && brushes[previous]) {
                    old=(HBRUSH)SelectObject(dc,brushes[previous]);
                    PatBlt(dc,(short)(off_x+run_start),(short)(off_y+y),
                       (short)(x-run_start),1,PATCOPY);
                    SelectObject(dc,old);
                }
                run_start=x;previous=index;
            }
        }
    }
    for(index=0;index<16;index++){
        if(brushes[index]) DeleteObject(brushes[index]);
    }
}
static void draw_ui(HDC dc) {
    TextOut(dc,5,3,(LPSTR)command_help,strlen(command_help));
    TextOut(dc,5,18,(LPSTR)text_label,5);
    TextOut(dc,38,18,(LPSTR)file_path,path_length);
    TextOut(dc,5,34,(LPSTR)(editing?text_edit:app_status),
            lstrlen(editing?text_edit:app_status));
    draw_image(dc,(RECT __far *)&paint_rect);
}
static BOOL register_class(HINSTANCE inst) {
    BOOL result;
    WNDCLASS *wc=(WNDCLASS *)LocalAlloc(LPTR,sizeof(WNDCLASS));
    if(!wc)return FALSE;
    /* 1985 Win1 WNDCLASS layout and API usage. */
    wc->style=CS_HREDRAW|CS_VREDRAW;
    wc->lpfnWndProc=(WNDPROC)View104Proc;
    wc->hInstance=inst;
    wc->hCursor=LoadCursor((HINSTANCE)0,IDC_ARROW);
    wc->hIcon=LoadIcon((HINSTANCE)0,IDI_APPLICATION);
    wc->hbrBackground=(HBRUSH)GetStockObject(WHITE_BRUSH);
    wc->lpszMenuName=(LPSTR)0;
    wc->lpszClassName=(LPSTR)app_class;
    result=RegisterClass((LPWNDCLASS)wc);
    LocalFree((HLOCAL)wc);
    return result;
}
long FAR PASCAL View104Proc(HWND h,unsigned msg,WORD wp,LONG lp) {
    PAINTSTRUCT ps;
    (void)lp;
    switch(msg) {
      case WM_CREATE:
        main_window=h;
        return 0L;
      case WM_DESTROY:
        if(pixels) {
            GlobalUnlock(pixels_handle);
            GlobalFree(pixels_handle);
            pixels=0;pixels_handle=0;
        }
        PostQuitMessage(0);
        return 0L;
      case WM_PAINT:
        BeginPaint(h,(LPPAINTSTRUCT)&ps);
        GetClientRect(h,(LPRECT)&paint_rect);
        draw_ui(ps.hdc);
        EndPaint(h,(LPPAINTSTRUCT)&ps);
        return 0L;
      case WM_CHAR:
        if(editing){
            if(wp==13){
                editing=0;
                open_image();
            } else if(wp==27) {
                editing=0;
                invalidate_image();
            } else if(wp==8){
                if(path_length>0)file_path[--path_length]=0;
                invalidate_image();
            } else if(wp>=32 && wp<=126 && path_length<PATH_CAP-1){
                file_path[path_length++]=(char)wp;
                file_path[path_length]=0;
                invalidate_image();
            }
        } else {
            switch(wp){
              case 'o':case 'O':open_image();break;
              case 'p':case 'P':editing=1;invalidate_image();break;
              case 'f':case 'F':fit=!fit;invalidate_image();break;
              case 'q':case 'Q':DestroyWindow(h);break;
            }
        }
        return 0L;
      case WM_LBUTTONDOWN:
        if(HIWORD(lp)<50) {
            if(LOWORD(lp)<140)open_image();
            else if(LOWORD(lp)<240){editing=1;invalidate_image();}
            else {fit=!fit;invalidate_image();}
        }
        return 0L;
    }
    return DefWindowProc(h,msg,wp,lp);
}
int PASCAL WinMain(HINSTANCE inst,HINSTANCE prev,LPSTR args,int show) {
    MSG msg;
    HWND window;
    (void)show;
    (void)args;
    if(!prev){
        if(!register_class(inst))return 1;
    } else {
        /* Windows 1.0 needs writable globals restored from the first instance. */
        GetInstanceData(prev,(PSTR)app_class,sizeof(app_class));
        GetInstanceData(prev,(PSTR)app_title,sizeof(app_title));
        GetInstanceData(prev,(PSTR)command_help,sizeof(command_help));
        GetInstanceData(prev,(PSTR)file_path,sizeof(file_path));
        GetInstanceData(prev,(PSTR)&path_length,sizeof(path_length));
        GetInstanceData(prev,(PSTR)app_status,sizeof(app_status));
    }
    /* Avoid child windows / modal dialogs until basic Win1 event handling
     * has been confirmed on actual Windows 1.04. */
    window=CreateWindow((LPSTR)app_class,(LPSTR)app_title,
       WS_TILEDWINDOW,0,0,0,0,(HWND)0,(HMENU)0,inst,(LPSTR)0);
    if(!window)return 2;
    main_window=window;
    ShowWindow(window,1);
    UpdateWindow(window);
    while(GetMessage((LPMSG)&msg,(HWND)0,0,0)) {
        TranslateMessage((LPMSG)&msg);
        DispatchMessage((LPMSG)&msg);
    }
    return (int)msg.wParam;
}
