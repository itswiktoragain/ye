/* Linux PID 1 proof of execution; not an Android component. */
#include <fcntl.h>
#include <unistd.h>
int main(void) {
    const char msg[] = "M23BOOTLAB: PID1 REACHED\n";
    int fd = open("/dev/console", O_WRONLY | O_NOCTTY);
    if (fd >= 0) { write(fd, msg, sizeof(msg)-1); close(fd); }
    write(STDOUT_FILENO, msg, sizeof(msg)-1);
    int km = open("/dev/kmsg", O_WRONLY | O_NONBLOCK);
    if (km >= 0) { write(km, "<6>M23BOOTLAB: PID1 REACHED\n", 28); close(km); }
    for (;;) sleep(1);
}
