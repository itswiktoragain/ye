/* ChronoView SDK probe. Built for 8086 Windows 1.0 using the real
 * Open Watcom windows1 startup and the 1985 Win1.01 SDK conventions.
 * Open Watcom additionally needs __loadds: FAR PASCAL alone reuses USER's DS
 * during callbacks, which can trash the application's strings and memory.
 * This contains no image codec, file I/O, custom menu, or child window.
 */
#include <windows.h>

/* Deliberately writable initialized DGROUP strings. A second Win1.0
 * instance needs GetInstanceData to recover data from the first. */
static char probe_class[16]="CVSDK104";
static char probe_title[26]="ChronoView SDK Test";
static char probe_text[44]="Window and text are working";

long FAR PASCAL __loadds SdkProbeProc(HWND h, unsigned message, WORD wp, LONG lp);

static BOOL PASCAL SetupProbe(HINSTANCE inst) {
    WNDCLASS *wc;
    BOOL ok;
    wc=(WNDCLASS *)LocalAlloc(LPTR,sizeof(WNDCLASS));
    if(!wc)return FALSE;
    wc->style=CS_HREDRAW|CS_VREDRAW;
    wc->lpfnWndProc=(WNDPROC)SdkProbeProc;
    wc->hInstance=inst;
    wc->hIcon=LoadIcon((HINSTANCE)0,IDI_APPLICATION);
    wc->hCursor=LoadCursor((HINSTANCE)0,IDC_ARROW);
    wc->hbrBackground=(HBRUSH)GetStockObject(WHITE_BRUSH);
    wc->lpszMenuName=(LPSTR)0;
    wc->lpszClassName=(LPSTR)probe_class;
    ok=RegisterClass((LPWNDCLASS)wc);
    LocalFree((HLOCAL)wc);
    return ok;
}

int PASCAL WinMain(HINSTANCE inst,HINSTANCE prev,LPSTR args,int show) {
    HWND window;
    MSG msg;
    (void)args;
    (void)show;
    if(!prev) {
        if(!SetupProbe(inst))return 1;
    } else {
        /* Required by Windows 1.0 if another copy of the program
         * still owns the registered window class. */
        GetInstanceData(prev,(PSTR)probe_class,sizeof(probe_class));
        GetInstanceData(prev,(PSTR)probe_title,sizeof(probe_title));
        GetInstanceData(prev,(PSTR)probe_text,sizeof(probe_text));
    }
    window=CreateWindow((LPSTR)probe_class,(LPSTR)probe_title,
         WS_TILEDWINDOW,0,0,0,0,(HWND)0,(HMENU)0,inst,(LPSTR)0);
    if(!window)return 2;
    ShowWindow(window,1);
    UpdateWindow(window);
    while(GetMessage((LPMSG)&msg,(HWND)0,0,0)){
        TranslateMessage((LPMSG)&msg);
        DispatchMessage((LPMSG)&msg);
    }
    return (int)msg.wParam;
}
long FAR PASCAL __loadds SdkProbeProc(HWND h,unsigned message,WORD wp,LONG lp) {
    PAINTSTRUCT ps;
    switch(message) {
      case WM_CREATE:
          return 0L;
      case WM_PAINT:
          BeginPaint(h,(LPPAINTSTRUCT)&ps);
          TextOut(ps.hdc,10,10,(LPSTR)probe_text,
                  sizeof("Window and text are working")-1);
          EndPaint(h,(LPPAINTSTRUCT)&ps);
          return 0L;
      case WM_DESTROY:
          PostQuitMessage(0);
          return 0L;
    }
    return DefWindowProc(h,message,wp,lp);
}
