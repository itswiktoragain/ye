/*
 * ChronoView 1.04: safe 8086 Windows 1.0 viewer.
 *
 * IMPORTANT: This is a NEW implementation, deliberately not linked to
 * the old sdkviewer.c or its stdio/DOS file-I/O routines.
 *
 * Native file handling: KERNEL._lopen / _lread / _lclose (Win1 API).
 * Runtime: Open Watcom -0 -ms -zu -zw -bg -s, windows1 NE loader.
 * Reentrant callbacks: FAR PASCAL __loadds, exported RESIDENT.
 * Allocation: one 16-KiB global block maximum. No dynamic painting
 * allocations, custom menus or child windows.
 */
#include <windows.h>
#include "win104picker.h"

#define CV_MAX_PIXELS 16384UL
#define CV_MAX_W 640
#define CV_MAX_H 480
#define CV_PATH_CAP 100
#define CV_COLOR_COUNT 16

static char cv_class[20] = "CHRONOVIEW104";
static char cv_title[22] = "ChronoView";
static char cv_path[CV_PATH_CAP] = "C:\\IMAGE.CV1";
static char cv_error_empty[] = "Type a path first.";
static char cv_error_file[] = "File not found / inaccessible.";
static char cv_error_format[] = "Not a CV1 file - convert PNG/JPEG/WebP.";
static char cv_error_size[] = "Max 16,384 pixels. Reconvert smaller.";
static char cv_error_memory[] = "Not enough free Windows memory.";
static char cv_error_data[] = "Invalid or truncated CV1 pixel data.";
static char cv_info_ready[] = "";
static char cv_info_loaded[] = "";
static char cv_info_brush[] = "Low graphics memory; image may be blank.";

static char *cv_status = cv_info_ready;
static unsigned cv_fit = 1;
static unsigned cv_width = 0, cv_height = 0;
static HGLOBAL cv_image_handle = (HGLOBAL)0;
static unsigned char __far *cv_pixels = (unsigned char __far *)0;
static HBRUSH cv_brushes[CV_COLOR_COUNT];
static HWND cv_window = (HWND)0;
static HINSTANCE cv_instance;
static FARPROC cv_picker_proc=(FARPROC)0;
static char cv_open_label[]="&Open...";
static char cv_clear_label[]="&Clear";
static char cv_fit_label[]="&Fit image";
static char cv_default_mask[]="*.CV1";
static char cv_empty[]="";
static char cv_error_picker[]="File selection is unavailable.";
static char cv_picker_spec[CV_PATH_CAP];
static char cv_picker_selected[CV_PATH_CAP];
static char cv_picker_name[CV_PATH_CAP];

/* Buffers in DGROUP, not on Windows' separate SS stack segment. */
static unsigned char cv_header[8];
static RECT cv_client;
static const unsigned char cv_ega[16][3] = {
  {0,0,0},      {0,0,170},    {0,170,0},   {0,170,170},
  {170,0,0},    {170,0,170},  {170,85,0},  {170,170,170},
  {85,85,85},   {85,85,255}, {85,255,85}, {85,255,255},
  {255,85,85},  {255,85,255},{255,255,85},{255,255,255}
};

long FAR PASCAL __loadds ChronoWndProc(HWND h, unsigned msg, WORD wp, LONG lp);
BOOL FAR PASCAL __loadds ChronoOpenDlgProc(HWND h,unsigned msg,WORD wp,LONG lp);

static void cv_repaint(void) {
    if(cv_window) InvalidateRect(cv_window,(LPRECT)0,TRUE);
}
static void cv_status_message(char *message) {
    cv_status = message;
    cv_repaint();
}
static void cv_close_image(void) {
    unsigned i;
    if(cv_pixels) {
        GlobalUnlock(cv_image_handle);
        cv_pixels=(unsigned char __far *)0;
    }
    if(cv_image_handle) {
        GlobalFree(cv_image_handle);
        cv_image_handle=(HGLOBAL)0;
    }
    cv_width=0;
    cv_height=0;
    for(i=0;i<CV_COLOR_COUNT;i++) {
        if(cv_brushes[i]) {
            DeleteObject(cv_brushes[i]);
            cv_brushes[i]=(HBRUSH)0;
        }
    }
}
static void cv_open_image(void) {
    HFILE file;
    unsigned w,h;
    unsigned long need,j;
    unsigned result;
    HGLOBAL next_handle;
    unsigned char __far *next_pixels;

    if(!cv_path[0]) {
        cv_status_message(cv_error_empty);
        return;
    }
    /* Windows 1.x KERNEL file APIs, NOT Watcom fopen/fread/fgetc,
       NOT DOS INT 21h from a USER.EXE callback. */
    file=_lopen((LPCSTR)cv_path,OF_READ);
    if(file==(HFILE)-1) {
        cv_status_message(cv_error_file);
        return;
    }
    result=_lread(file,(void __huge *)cv_header,8);
    if(result!=8 || cv_header[0]!='C' || cv_header[1]!='V'
        || cv_header[2]!='1' || cv_header[3]!=26) {
        _lclose(file);
        cv_status_message(cv_error_format);
        return;
    }
    w=(unsigned)cv_header[4] | ((unsigned)cv_header[5]<<8);
    h=(unsigned)cv_header[6] | ((unsigned)cv_header[7]<<8);
    need=(unsigned long)w*(unsigned long)h;
    if(!w || !h || w>CV_MAX_W || h>CV_MAX_H || need>CV_MAX_PIXELS) {
        _lclose(file);
        cv_status_message(cv_error_size);
        return;
    }
    next_handle=GlobalAlloc(GMEM_MOVEABLE,(DWORD)need);
    if(!next_handle) {
        _lclose(file);
        cv_status_message(cv_error_memory);
        return;
    }
    next_pixels=(unsigned char __far *)GlobalLock(next_handle);
    if(!next_pixels) {
        GlobalFree(next_handle);
        _lclose(file);
        cv_status_message(cv_error_memory);
        return;
    }
    result=_lread(file,(void __huge *)next_pixels,(UINT)need);
    _lclose(file);
    if(result!=(UINT)need) {
        GlobalUnlock(next_handle);
        GlobalFree(next_handle);
        cv_status_message(cv_error_data);
        return;
    }
    for(j=0;j<need;j++) {
        if(next_pixels[(unsigned)j]>=CV_COLOR_COUNT) {
            GlobalUnlock(next_handle);
            GlobalFree(next_handle);
            cv_status_message(cv_error_data);
            return;
        }
    }

    /* Only replace the old picture after the new one passes validation. */
    cv_close_image();
    cv_image_handle=next_handle;
    cv_pixels=next_pixels;
    cv_width=w;
    cv_height=h;
    for(j=0;j<CV_COLOR_COUNT;j++) {
        cv_brushes[(unsigned)j]=CreateSolidBrush(RGB(
            cv_ega[(unsigned)j][0],cv_ega[(unsigned)j][1],cv_ega[(unsigned)j][2]));
        if(!cv_brushes[(unsigned)j]) {
            cv_status_message(cv_info_brush);
            return;
        }
    }
    cv_status_message(cv_info_loaded);
}
static void cv_draw_image(HDC dc,RECT __far *client) {
    unsigned view_w,view_h,draw_w,draw_h;
    unsigned row,col,sx,sy,color,last,run;
    short offset_x,offset_y;
    HBRUSH original;

    if(!cv_pixels || !cv_width || !cv_height) return;
    if(client->right<=client->left || client->bottom<=client->top+57) return;
    view_w=(unsigned)(client->right-client->left);
    view_h=(unsigned)(client->bottom-client->top-57);
    /* Never upscale. Old GDI must not redraw tens of thousands of cells
       for a tiny bitmap whenever the user drags a window. */
    draw_w=cv_width; draw_h=cv_height;
    if(cv_fit && (draw_w>view_w || draw_h>view_h)) {
        if((unsigned long)view_w*cv_height
           <= (unsigned long)view_h*cv_width) {
            draw_w=view_w;
            draw_h=(unsigned)((unsigned long)draw_w*cv_height/cv_width);
        } else {
            draw_h=view_h;
            draw_w=(unsigned)((unsigned long)draw_h*cv_width/cv_height);
        }
    }
    if(!draw_w || !draw_h) return;
    offset_x=(short)(((int)view_w-(int)draw_w)/2);
    offset_y=(short)(51+((int)view_h-(int)draw_h)/2);

    for(row=0;row<draw_h;row++) {
        sy=(unsigned)((unsigned long)row*cv_height/draw_h);
        run=0;
        last=65535;
        for(col=0;col<=draw_w;col++) {
            color=65535;
            if(col<draw_w) {
                sx=(unsigned)((unsigned long)col*cv_width/draw_w);
                color=(unsigned)cv_pixels[
                   (unsigned)((unsigned long)sy*cv_width+sx)];
            }
            if(color!=last) {
                if(last<CV_COLOR_COUNT && col>run && cv_brushes[last]) {
                    original=(HBRUSH)SelectObject(dc,cv_brushes[last]);
                    PatBlt(dc,(short)(offset_x+run),(short)(offset_y+row),
                           (short)(col-run),1,PATCOPY);
                    SelectObject(dc,original);
                }
                run=col;
                last=color;
            }
        }
    }
}

/* Use DlgDirList for file/directory/drive discovery (Windows 1.0 API). */
static void cv_copy(char *dest,const char *src,unsigned limit) {
    unsigned i=0;
    if(!limit)return;
    while(i+1<limit && src[i]){dest[i]=src[i];i++;}
    dest[i]=0;
}
static unsigned cv_length(const char *src) {
    unsigned i=0;
    while(src[i])i++;
    return i;
}
static void cv_refresh_files(HWND dlg,const char *pattern) {
    cv_copy(cv_picker_spec,pattern,CV_PATH_CAP);
    if(DlgDirList(dlg,(LPSTR)cv_picker_spec,CV_IDC_FILES,
                   CV_IDC_DIRECTORY,CV_DDL_FOLDERS_DRIVES))
        SetDlgItemText(dlg,CV_IDC_FILENAME,(LPCSTR)cv_empty);
}
static void cv_enter_folder(HWND dlg,const char *folder) {
    unsigned n;
    cv_copy(cv_picker_spec,folder,CV_PATH_CAP);
    n=cv_length(cv_picker_spec);
    if(!n || n+7>=CV_PATH_CAP)return;
    if(cv_picker_spec[n-1]!='\\' && cv_picker_spec[n-1]!=':' &&
       cv_picker_spec[n-1]!='/') {
        cv_picker_spec[n++]='\\';
        cv_picker_spec[n]=0;
    }
    cv_copy(cv_picker_spec+n,cv_default_mask,CV_PATH_CAP-n);
    cv_refresh_files(dlg,cv_picker_spec);
}
static void cv_accept_file(HWND dlg) {
    unsigned i,n;
    GetDlgItemText(dlg,CV_IDC_FILENAME,cv_picker_name,CV_PATH_CAP);
    n=cv_length(cv_picker_name);
    if(!n)return;
    for(i=0;i<n;i++) {
        if(cv_picker_name[i]=='*' || cv_picker_name[i]=='?') {
            cv_refresh_files(dlg,cv_picker_name);
            return;
        }
    }
    if(cv_picker_name[n-1]=='\\' || cv_picker_name[n-1]=='/' ||
       cv_picker_name[n-1]==':') {
        cv_enter_folder(dlg,cv_picker_name);
        return;
    }
    cv_copy(cv_path,cv_picker_name,CV_PATH_CAP);
    EndDialog(dlg,IDOK);
}
BOOL FAR PASCAL __loadds ChronoOpenDlgProc(HWND dlg,unsigned msg,
                                            WORD wp,LONG lp) {
    int is_directory;
    switch(msg) {
      case WM_INITDIALOG:
        cv_refresh_files(dlg,cv_default_mask);
        return TRUE;
      case WM_COMMAND:
        switch(wp) {
          case IDCANCEL: EndDialog(dlg,IDCANCEL); return TRUE;
          case IDOK: cv_accept_file(dlg); return TRUE;
          case CV_IDC_REFRESH:
            cv_refresh_files(dlg,cv_default_mask);
            return TRUE;
          case CV_IDC_FILES:
            if(HIWORD(lp)==LBN_SELCHANGE || HIWORD(lp)==LBN_DBLCLK) {
                cv_picker_selected[0]=0;
                is_directory=DlgDirSelect(dlg,cv_picker_selected,CV_IDC_FILES);
                if(is_directory) {
                    if(HIWORD(lp)==LBN_DBLCLK)
                        cv_enter_folder(dlg,cv_picker_selected);
                    else
                        SetDlgItemText(dlg,CV_IDC_FILENAME,
                                       (LPCSTR)cv_empty);
                } else if(cv_picker_selected[0]) {
                    SetDlgItemText(dlg,CV_IDC_FILENAME,
                                   (LPCSTR)cv_picker_selected);
                    if(HIWORD(lp)==LBN_DBLCLK)cv_accept_file(dlg);
                }
            }
            return TRUE;
        }
        break;
    }
    return FALSE;
}
static void cv_show_open_dialog(HWND owner) {
    int outcome;
    if(!cv_picker_proc) {
        cv_status_message(cv_error_picker);
        return;
    }
    outcome=DialogBox(cv_instance,MAKEINTRESOURCE(CV_IDD_OPEN),
                      owner,(DLGPROC)cv_picker_proc);
    if(outcome==IDOK)cv_open_image();
    else if(outcome<0)cv_status_message(cv_error_picker);
}
static void cv_draw(HDC dc) {
    /* Startup remains entirely white until first image or error. */
    if(cv_pixels && cv_width && cv_height)
        cv_draw_image(dc,(RECT __far *)&cv_client);
    else if(cv_status!=cv_info_ready && cv_status!=cv_info_loaded)
        TextOut(dc,8,8,(LPSTR)cv_status,lstrlen((LPCSTR)cv_status));
}
long FAR PASCAL __loadds ChronoWndProc(HWND h,unsigned message,
                                      WORD wp,LONG lp) {
    PAINTSTRUCT ps;
    switch(message) {
      case WM_CREATE: cv_window=h; return 0L;
      case WM_SYSCOMMAND:
        switch(wp) {
          case CV_IDM_OPEN: cv_show_open_dialog(h); return 0L;
          case CV_IDM_CLEAR:
            cv_close_image();
            cv_status=cv_info_ready;
            cv_repaint();
            return 0L;
          case CV_IDM_FIT:
            cv_fit=!cv_fit;
            cv_repaint();
            return 0L;
        }
        break;
      case WM_CHAR:
        if(wp=='o' || wp=='O') {cv_show_open_dialog(h);return 0L;}
        if(wp=='f' || wp=='F') {cv_fit=!cv_fit;cv_repaint();return 0L;}
        if(wp=='q' || wp=='Q') {DestroyWindow(h);return 0L;}
        return 0L;
      case WM_PAINT:
        BeginPaint(h,(LPPAINTSTRUCT)&ps);
        if(ps.hdc) {
            GetClientRect(h,(LPRECT)&cv_client);
            cv_draw(ps.hdc);
        }
        EndPaint(h,(LPPAINTSTRUCT)&ps);
        return 0L;
      case WM_DESTROY:
        cv_close_image();
        cv_window=(HWND)0;
        PostQuitMessage(0);
        return 0L;
    }
    return DefWindowProc(h,message,wp,lp);
}
static BOOL cv_register(HINSTANCE instance) {
    BOOL result;
    WNDCLASS *wc=(WNDCLASS *)LocalAlloc(LPTR,sizeof(WNDCLASS));
    if(!wc)return FALSE;
    wc->style=CS_HREDRAW|CS_VREDRAW;
    wc->lpfnWndProc=(WNDPROC)ChronoWndProc;
    wc->cbClsExtra=0;
    wc->cbWndExtra=0;
    wc->hInstance=instance;
    wc->hIcon=LoadIcon((HINSTANCE)0,IDI_APPLICATION);
    wc->hCursor=LoadCursor((HINSTANCE)0,IDC_ARROW);
    wc->hbrBackground=(HBRUSH)GetStockObject(WHITE_BRUSH);
    wc->lpszMenuName=(LPSTR)0;
    wc->lpszClassName=(LPSTR)cv_class;
    result=RegisterClass((LPWNDCLASS)wc);
    LocalFree((HLOCAL)wc);
    return result;
}

int PASCAL WinMain(HINSTANCE instance,HINSTANCE previous,
                   LPSTR command,int show) {
    MSG message;
    HWND window;
    HMENU sysmenu;
    (void)command;(void)show;
    if(previous)return 0;
    cv_instance=instance;
    if(!cv_register(instance))return 1;
    window=CreateWindow((LPSTR)cv_class,(LPSTR)cv_title,
         WS_TILEDWINDOW,0,0,0,0,(HWND)0,(HMENU)0,instance,(LPSTR)0);
    if(!window)return 2;
    cv_window=window;
    cv_picker_proc=MakeProcInstance((FARPROC)ChronoOpenDlgProc,instance);
    sysmenu=GetSystemMenu(window,FALSE);
    if(sysmenu) {
        ChangeMenu(sysmenu,0,(LPSTR)0,0,MF_APPEND|MF_SEPARATOR);
        ChangeMenu(sysmenu,0,(LPSTR)cv_open_label,
                   CV_IDM_OPEN,MF_APPEND|MF_STRING);
        ChangeMenu(sysmenu,0,(LPSTR)cv_clear_label,
                   CV_IDM_CLEAR,MF_APPEND|MF_STRING);
        ChangeMenu(sysmenu,0,(LPSTR)cv_fit_label,
                   CV_IDM_FIT,MF_APPEND|MF_STRING);
    }
    ShowWindow(window,1);
    while(GetMessage((LPMSG)&message,(HWND)0,0,0)) {
        TranslateMessage((LPMSG)&message);
        DispatchMessage((LPMSG)&message);
    }
    if(cv_picker_proc)FreeProcInstance(cv_picker_proc);
    return (int)message.wParam;
}
