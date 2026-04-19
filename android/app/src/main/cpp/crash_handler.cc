// Native crash capture:install signal handlers that write a minimal crash
// report (signal number, fault address, build info) to a fixed path provided
// by the Java side, then re-raise the signal so the system produces a tombstone.

#include "crash_handler.h"
#include <jni.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <sys/types.h>
#include <time.h>

static char g_crash_path[512] = {0};
static bool g_installed = false;

static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        default: return "UNKNOWN";
    }
}

// async-signal-safe write helpers
static void write_str(int fd, const char* s) {
    if (!s) return;
    size_t n = 0; while (s[n]) n++;
    (void)write(fd, s, n);
}
static void write_u64(int fd, unsigned long long v) {
    char buf[32]; int pos = 31; buf[pos--] = 0;
    if (v == 0) buf[pos--] = '0';
    while (v > 0 && pos >= 0) { buf[pos--] = '0' + (v % 10); v /= 10; }
    write_str(fd, buf + pos + 1);
}

static void handler(int sig, siginfo_t* info, void*) {
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        write_str(fd, "=== native crash ===\n");
        write_str(fd, "Signal: "); write_str(fd, signal_name(sig));
        write_str(fd, " ("); write_u64(fd, (unsigned)sig); write_str(fd, ")\n");
        write_str(fd, "si_code: "); write_u64(fd, (unsigned)info->si_code); write_str(fd, "\n");
        write_str(fd, "si_addr: 0x");
        unsigned long long a = (unsigned long long)info->si_addr;
        char hex[32]; int p = 31; hex[p--] = 0;
        if (a == 0) hex[p--] = '0';
        while (a > 0 && p >= 0) {
            unsigned d = a & 0xF;
            hex[p--] = d < 10 ? '0' + d : 'a' + (d - 10);
            a >>= 4;
        }
        write_str(fd, hex + p + 1); write_str(fd, "\n");
        write_str(fd, "pid: "); write_u64(fd, getpid()); write_str(fd, "\n");
        write_str(fd, "tid: "); write_u64(fd, gettid()); write_str(fd, "\n");
        time_t t = time(nullptr);
        write_str(fd, "time_t: "); write_u64(fd, (unsigned long long)t); write_str(fd, "\n");
        close(fd);
    }
    // 让系统继续处理(tombstone + 进程终止)
    signal(sig, SIG_DFL);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_autoclicker_app_OcrNative_installCrashHandler(JNIEnv* env, jclass, jstring jPath) {
    if (g_installed) return;
    if (jPath == nullptr) return;
    const char* p = env->GetStringUTFChars(jPath, nullptr);
    strncpy(g_crash_path, p, sizeof(g_crash_path) - 1);
    env->ReleaseStringUTFChars(jPath, p);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    g_installed = true;
}
