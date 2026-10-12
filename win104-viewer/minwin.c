/* Minimal Windows 1.x loader test: deliberately no USER/GDI calls,
 * icon, class, graphics, file I/O, or message loop.
 *
 * On success, MS-DOS Executive should become responsive immediately.
 * If Windows freezes even running this, the viewer code is not the cause.
 */
#include <windows.h>
int PASCAL WinMain(HINSTANCE inst,HINSTANCE previous,LPSTR args,int show) {
    (void)inst;
    (void)previous;
    (void)args;
    (void)show;
    return 0;
}
