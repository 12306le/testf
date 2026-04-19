// Native crash capture with backtrace (via _Unwind_Backtrace + dladdr).
// Also redirects stdout/stderr to a file so Paddle Lite's LOG(FATAL)/CHECK
// message (written to stderr just before abort) is captured for later upload.

#include "crash_handler.h"
#include <jni.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <dlfcn.h>
#include <unwind.h>
#include <stdint.h>

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
static void write_hex(int fd, unsigned long long v) {
    char buf[32]; int pos = 31; buf[pos--] = 0;
    if (v == 0) buf[pos--] = '0';
    while (v > 0 && pos >= 0) {
        unsigned d = v & 0xF;
        buf[pos--] = d < 10 ? '0' + d : 'a' + (d - 10);
        v >>= 4;
    }
    write_str(fd, buf + pos + 1);
}

// Backtrace via _Unwind_Backtrace
struct BtState { uintptr_t* cur; uintptr_t* end; };
static _Unwind_Reason_Code bt_cb(struct _Unwind_Context* ctx, void* arg) {
    BtState* s = (BtState*)arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc && s->cur < s->end) *s->cur++ = pc;
    return _URC_NO_REASON;
}

static void dump_backtrace(int fd) {
    uintptr_t frames[48] = {0};
    BtState s = { frames, frames + 48 };
    _Unwind_Backtrace(bt_cb, &s);
    int n = (int)(s.cur - frames);
    write_str(fd, "\nbacktrace (");
    write_u64(fd, (unsigned)n);
    write_str(fd, " frames):\n");
    for (int i = 0; i < n; i++) {
        write_str(fd, "  #");
        write_u64(fd, (unsigned)i);
        write_str(fd, "  pc 0x");
        write_hex(fd, frames[i]);
        Dl_info info; memset(&info, 0, sizeof(info));
        if (dladdr((void*)frames[i], &info)) {
            if (info.dli_fname) {
                write_str(fd, "  ");
                // basename only
                const char* b = strrchr(info.dli_fname, '/');
                write_str(fd, b ? b + 1 : info.dli_fname);
                if (info.dli_fbase) {
                    write_str(fd, " +0x");
                    write_hex(fd, (uintptr_t)frames[i] - (uintptr_t)info.dli_fbase);
                }
            }
            if (info.dli_sname) {
                write_str(fd, "  ");
                write_str(fd, info.dli_sname);
            }
        }
        write_str(fd, "\n");
    }
}

static void dump_maps(int fd) {
    write_str(fd, "\nmaps (libNative, libpaddle, libopencv):\n");
    int mf = open("/proc/self/maps", O_RDONLY);
    if (mf < 0) return;
    char buf[4096];
    while (true) {
        ssize_t n = read(mf, buf, sizeof(buf) - 1);
        if (n <= 0) break;
        buf[n] = 0;
        // only lines mentioning one of the libs we care about
        char* line = buf;
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                buf[i] = 0;
                if (strstr(line, "libNative.so") || strstr(line, "libpaddle_light") ||
                    strstr(line, "libopencv") || strstr(line, "libc++_shared")) {
                    write_str(fd, "  "); write_str(fd, line); write_str(fd, "\n");
                }
                line = buf + i + 1;
            }
        }
    }
    close(mf);
}

static void handler(int sig, siginfo_t* info, void*) {
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        write_str(fd, "=== native crash ===\n");
        write_str(fd, "Signal: "); write_str(fd, signal_name(sig));
        write_str(fd, " ("); write_u64(fd, (unsigned)sig); write_str(fd, ")\n");
        write_str(fd, "si_code: "); write_u64(fd, (unsigned)info->si_code); write_str(fd, "\n");
        write_str(fd, "si_addr: 0x"); write_hex(fd, (unsigned long long)info->si_addr);
        write_str(fd, "\npid: "); write_u64(fd, getpid());
        write_str(fd, "\ntid: "); write_u64(fd, gettid());
        time_t t = time(nullptr);
        write_str(fd, "\ntime_t: "); write_u64(fd, (unsigned long long)t);
        write_str(fd, "\n");
        dump_backtrace(fd);
        dump_maps(fd);
        close(fd);
    }
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

// Redirect native stdout/stderr (Paddle Lite LOG(FATAL) etc.) to a file so
// the Kotlin side can upload the last messages along with the crash report.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_autoclicker_app_OcrNative_redirectStdio(JNIEnv* env, jclass, jstring jPath) {
    if (jPath == nullptr) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(jPath, nullptr);
    // O_APPEND so each run adds to it; we truncate externally when it gets large.
    int fd = open(p, O_WRONLY | O_CREAT | O_APPEND, 0600);
    env->ReleaseStringUTFChars(jPath, p);
    if (fd < 0) return JNI_FALSE;
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    close(fd);
    // marker so we can see where a new session starts
    fprintf(stderr, "[stdio] attached pid=%d\n", getpid());
    return JNI_TRUE;
}
