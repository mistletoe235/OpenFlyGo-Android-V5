#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static int64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int main(int argc, char **argv) {
    const char *socket_path = argc > 1 ? argv[1] : "/dev/socket/fpv_sock";
    const char *output_path = argc > 2 ? argv[2] : "/sdcard/fpv_probe.bin";
    const size_t max_bytes = argc > 3 ? strtoull(argv[3], NULL, 10) : 8 * 1024 * 1024;
    int socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) {
        fprintf(stderr, "socket: %s\n", strerror(errno));
        return 2;
    }

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(socket_path) >= sizeof(address.sun_path)) {
        fprintf(stderr, "socket path too long: %s\n", socket_path);
        return 2;
    }
    strcpy(address.sun_path, socket_path);
    if (connect(socket_fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        fprintf(stderr, "connect %s: %s\n", socket_path, strerror(errno));
        return 3;
    }

    int output_fd = open(output_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0664);
    if (output_fd < 0) {
        fprintf(stderr, "open %s: %s\n", output_path, strerror(errno));
        return 4;
    }

    uint8_t buffer[256 * 1024];
    size_t total = 0;
    int64_t deadline = monotonic_ms() + 10000;
    while (total < max_bytes && monotonic_ms() < deadline) {
        struct pollfd poll_fd = {.fd = socket_fd, .events = POLLIN};
        int result = poll(&poll_fd, 1, 1000);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) {
            fprintf(stderr, "poll: %s\n", strerror(errno));
            return 5;
        }
        if (result == 0) continue;
        ssize_t count = read(socket_fd, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            fprintf(stderr, "read: %s\n", strerror(errno));
            return 6;
        }
        if (count == 0) break;
        ssize_t written = write(output_fd, buffer, (size_t)count);
        if (written != count) {
            fprintf(stderr, "write: %s\n", strerror(errno));
            return 7;
        }
        total += (size_t)count;
    }

    fsync(output_fd);
    close(output_fd);
    close(socket_fd);
    printf("socket=%s bytes=%zu output=%s\n", socket_path, total, output_path);
    return total > 0 ? 0 : 8;
}
