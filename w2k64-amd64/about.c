/* Community Windows 2000 x64 prototype demo About utility. Doesn't modify winver. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>

static HBITMAP banner;
static HFONT titlefont, subfont, smallfont;
static void printat(HDC dc, int x, int y, int w, int h, HFONT font, COLORREF color, LPCSTR text) {
    RECT rect = {x, y, x+w, y+h};
    HGDIOBJ old = SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, color);
    DrawTextA(dc, text, -1, &rect, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX);
    SelectObject(dc, old);
}
static LRESULT CALLBACK windowproc(HWND w, UINT m, WPARAM wp, LPARAM lp) {
    switch(m) {
    case WM_CREATE: {
        char file[MAX_PATH];
        DWORD n = GetModuleFileNameA(NULL, file, sizeof(file));
        while(n && file[n-1]!='\\' && file[n-1]!='/') n--;
        lstrcpyA(file+n, "ABOUT.BMP");
        banner = (HBITMAP)LoadImageA(NULL, file, IMAGE_BITMAP, 0, 0, LR_LOADFROMFILE);
        titlefont=CreateFontA(20,0,0,0,FW_BOLD,0,0,0,DEFAULT_CHARSET,0,0,0,0,"Tahoma");
        subfont=CreateFontA(15,0,0,0,FW_BOLD,0,0,0,DEFAULT_CHARSET,0,0,0,0,"Tahoma");
        smallfont=CreateFontA(14,0,0,0,FW_NORMAL,0,0,0,DEFAULT_CHARSET,0,0,0,0,"Tahoma");
        CreateWindowA("BUTTON","OK",WS_CHILD|WS_VISIBLE|BS_DEFPUSHBUTTON,430,273,80,25,w,(HMENU)100,GetModuleHandleA(NULL),NULL);
        return 0;
    }
    case WM_PAINT: {
        PAINTSTRUCT ps; HDC dc=BeginPaint(w,&ps);
        RECT all;GetClientRect(w,&all);FillRect(dc,&all,GetSysColorBrush(COLOR_BTNFACE));
        RECT b={12,12,518,117};FillRect(dc,&b,GetSysColorBrush(COLOR_WINDOW));
        if(banner){BITMAP bmp; GetObject(banner,sizeof(bmp),&bmp);
          HDC mem=CreateCompatibleDC(dc); HGDIOBJ old=SelectObject(mem,banner);
          BitBlt(dc,14,14,bmp.bmWidth,bmp.bmHeight,mem,0,0,SRCCOPY);
          SelectObject(mem,old);DeleteDC(mem);
        }
        printat(dc,20,125,500,25,titlefont,RGB(20,20,20),"Windows 2000 Professional");
        printat(dc,20,157,510,22,subfont,RGB(34,55,96),"(64-bit edition, a community made prototype)");
        printat(dc,20,193,510,19,smallfont,RGB(40,40,40),"Architecture: AMD64 / x86-64");
        printat(dc,20,215,510,19,smallfont,RGB(40,40,40),"Core: Windows NT 5.2 (XP Professional x64)");
        printat(dc,20,237,510,19,smallfont,RGB(40,40,40),"WOW64: 32-bit Windows application support");
        printat(dc,20,259,400,19,smallfont,RGB(40,40,40),"16-bit: external compatibility solution required");
        EndPaint(w,&ps);return 0;
    }
    case WM_COMMAND: if(LOWORD(wp)==100) {DestroyWindow(w);return 0;} break;
    case WM_DESTROY: if(banner)DeleteObject(banner);if(titlefont)DeleteObject(titlefont);
       if(subfont)DeleteObject(subfont);if(smallfont)DeleteObject(smallfont);PostQuitMessage(0);return 0;
    }
    return DefWindowProcA(w,m,wp,lp);
}
int WINAPI WinMain(HINSTANCE h, HINSTANCE p, LPSTR l, int n) {
  WNDCLASSA c={0};c.hInstance=h;c.lpfnWndProc=windowproc;c.lpszClassName="Win2K64PrototypeAbout";
  c.hCursor=LoadCursor(NULL,IDC_ARROW);c.hIcon=LoadIcon(NULL,IDI_APPLICATION);c.hbrBackground=GetSysColorBrush(COLOR_BTNFACE);
  if(!RegisterClassA(&c)) return 2;
  HWND w=CreateWindowA(c.lpszClassName,"About Windows",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX,
      CW_USEDEFAULT,CW_USEDEFAULT,545,350,NULL,NULL,h,NULL);
  if(!w)return 3;
  ShowWindow(w,SW_SHOW);UpdateWindow(w);
  MSG m;while(GetMessage(&m,NULL,0,0)>0){TranslateMessage(&m);DispatchMessage(&m);}return 0;
}
