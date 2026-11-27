#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/types.h>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc < 5 || std::strcmp(argv[1], "--pgid") != 0) {
        std::fprintf(stderr, "usage: process_group_launcher --pgid <0|pgid> -- <executable> [args...]\n");
        return 64;
    }

    char* end = nullptr;
    errno = 0;
    const long requestedGroup = std::strtol(argv[2], &end, 10);
    if (errno != 0 || end == argv[2] || *end != '\0' || requestedGroup < 0) {
        std::fprintf(stderr, "invalid process group id: %s\n", argv[2]);
        return 64;
    }
    if (std::strcmp(argv[3], "--") != 0 || argv[4] == nullptr || argv[4][0] == '\0') {
        std::fprintf(stderr, "missing executable after --\n");
        return 64;
    }

    // Set the group before execv replaces this helper with Box64.
    // pgid=0 makes this Box64 PID the process-group leader; a positive PGID
    // joins the existing group in the same app session.
    if (setpgid(0, static_cast<pid_t>(requestedGroup)) != 0) {
        std::fprintf(stderr, "setpgid(0, %ld) failed: errno=%d (%s)\n",
                     requestedGroup, errno, std::strerror(errno));
        return 126;
    }

    execv(argv[4], &argv[4]);
    std::fprintf(stderr, "execv(%s) failed: errno=%d (%s)\n",
                 argv[4], errno, std::strerror(errno));
    return 127;
}