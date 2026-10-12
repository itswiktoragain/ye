/* Minimal Windows 1.04 sanity test based on the 1985/1986 Hello SDK.
 * There is deliberately no file I/O, menu, dialog, malloc, or child control.
 * If this fails, investigate Win1.04 loader/ABI or the emulator itself.
 */
#include <windows.h>

static char class_name[]="CVPROBE104";
static char caption[]="ChronoView Win1 Probe";
static char greeting[]="Windows 1.04 callback and text output OK";
static WNDCLASS cls;

long FAR PASCAL __loadds ProbeProc(HWND h,unsigned msg,WORD wp,LONG lp) {
    PAINTSTRUCT ps;
    if(msg==WM_PAINT) {
        BeginPaint(h,&ps);
        TextOut(ps.hdc,6,18,greeting,sizeof(greeting)-1);
        EndPaint(h,&ps);
        return 0;
    }
    if(msg==WM_DESTROY) {
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProc(h,msg,wp,lp);
}
int PASCAL WinMain(HINSTANCE inst,HINSTANCE previous,LPSTR args,int cmdShow) {
    HWND window;
    MSG message;
    (void)args;
    (void)cmdShow;
    if(!previous) {
        cls.style=CS_HREDRAW|CS_VREDRAW;
        cls.lpfnWndProc=(WNDPROC)ProbeProc;
        cls.cbClsExtra=0;
        cls.cbWndExtra=0;
        cls.hInstance=inst;
        cls.hIcon=LoadIcon(0,IDI_APPLICATION);
        cls.hCursor=LoadCursor(0,IDC_ARROW);
        cls.hbrBackground=(HBRUSH)GetStockObject(WHITE_BRUSH);
        cls.lpszMenuName=(LPSTR)0;
        cls.lpszClassName=class_name;
        if(!RegisterClass(&cls))return 1;
    }
    window=CreateWindow(class_name,caption,WS_TILEDWINDOW,
                        0,0,0,0,(HWND)0,(HMENU)0,inst,(LPSTR)0);
    if(!window)return 2;
    ShowWindow(window,1);
    while(GetMessage(&message,0,0,0)){
        TranslateMessage(&message);
        DispatchMessage(&message);
    }
    return (int)message.wParam;
}
