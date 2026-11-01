// SPDX-License-Identifier: MIT
// DriverLab recorder (libdlrec.so), loaded with LD_PRELOAD into every test
// process DriverLab starts.
//
// - Always: makes stdout line-buffered (the CTS never flushes its progress).
// - DLREC_PATH set: records every KGSL GPU submit of the system (Qualcomm)
//   driver as a freedreno ".rd" capture (gzip), the format Mesa's cffdump and
//   DriverLab read. Driver requests are never changed: buffer flags are left
//   alone and GPU addresses are looked up with GPUOBJ_INFO when needed.
// - DLREC_LOG set: writes a log of the driver's kernel calls in the same
//   format as Rob Clark's freedreno libwrap ("-" = stdout, so it interleaves
//   with the test's own output like libwrap did; otherwise a file path).
// - Exports rd_start/rd_end/rd_write_section, which the freedreno GLES tests
//   call (weak symbols) to mark test cases inside the capture.
//
// Other variables: DLREC_BUF_CAP (skip non-command buffers bigger than this
// many bytes, 0 = all), DLREC_TESTNAME (RD_TEST section text).
//
// The .rd format and the log format follow freedreno libwrap (Rob Clark, MIT).
#define BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD 1
#define _GNU_SOURCE
#include <ctype.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <zlib.h>

/* ---- KGSL UAPI subset (layouts as in the kernel's msm_kgsl.h, LP64) ---- */
#define KGSL_IOC_TYPE 0x09
struct kgsl_device_getproperty { uint32_t type; void *value; size_t sizebytes; };
#define KGSL_PROP_DEVICE_INFO 0x1
struct kgsl_devinfo {
    uint32_t device_id; uint32_t chip_id; uint32_t mmu_enabled;
    unsigned long gmem_gpubaseaddr; uint32_t gpu_id; size_t gmem_sizebytes;
};
struct kgsl_drawctxt_create { uint32_t flags; uint32_t drawctxt_id; };
struct kgsl_gpumem_alloc_id {
    uint32_t id; uint32_t flags; size_t size; size_t mmapsize;
    unsigned long gpuaddr; unsigned long __pad[2];
};
struct kgsl_gpumem_free_id { uint32_t id; uint32_t __pad; };
struct kgsl_gpuobj_alloc {
    uint64_t size; uint64_t flags; uint64_t va_len; uint64_t mmapsize;
    uint32_t id; uint32_t metadata_len; uint64_t metadata;
};
struct kgsl_gpuobj_free { uint64_t flags; uint64_t priv; uint32_t id; uint32_t type; uint32_t len; };
struct kgsl_gpuobj_info {
    uint64_t gpuaddr; uint64_t flags; uint64_t size; uint64_t va_len;
    uint64_t va_addr; uint32_t id;
};
struct kgsl_command_object { uint64_t offset; uint64_t gpuaddr; uint64_t size; uint32_t flags; uint32_t id; };
struct kgsl_gpu_command {
    uint64_t flags; uint64_t cmdlist; uint32_t cmdsize; uint32_t numcmds;
    uint64_t objlist; uint32_t objsize; uint32_t numobjs;
    uint64_t synclist; uint32_t syncsize; uint32_t numsyncs;
    uint32_t context_id; uint32_t timestamp;
};
#define IOCTL_KGSL_DEVICE_GETPROPERTY _IOWR(KGSL_IOC_TYPE, 0x2, struct kgsl_device_getproperty)
#define IOCTL_KGSL_DRAWCTXT_CREATE    _IOWR(KGSL_IOC_TYPE, 0x13, struct kgsl_drawctxt_create)
#define IOCTL_KGSL_GPUMEM_ALLOC_ID    _IOWR(KGSL_IOC_TYPE, 0x34, struct kgsl_gpumem_alloc_id)
#define IOCTL_KGSL_GPUMEM_FREE_ID     _IOWR(KGSL_IOC_TYPE, 0x35, struct kgsl_gpumem_free_id)
#define IOCTL_KGSL_GPUOBJ_ALLOC       _IOWR(KGSL_IOC_TYPE, 0x45, struct kgsl_gpuobj_alloc)
#define IOCTL_KGSL_GPUOBJ_FREE        _IOW(KGSL_IOC_TYPE, 0x46, struct kgsl_gpuobj_free)
#define IOCTL_KGSL_GPUOBJ_INFO        _IOWR(KGSL_IOC_TYPE, 0x47, struct kgsl_gpuobj_info)
#define IOCTL_KGSL_GPU_COMMAND        _IOWR(KGSL_IOC_TYPE, 0x4A, struct kgsl_gpu_command)

/* ---- freedreno rd format (Mesa src/freedreno/common/redump.h) ---- */
enum rd_sect_type { RD_NONE, RD_TEST, RD_CMD, RD_GPUADDR, RD_CONTEXT, RD_CMDSTREAM,
       RD_CMDSTREAM_ADDR, RD_PARAM, RD_FLUSH, RD_PROGRAM, RD_VERT_SHADER, RD_FRAG_SHADER,
       RD_BUFFER_CONTENTS, RD_GPU_ID, RD_CHIP_ID };

static const char *ioctl_name(unsigned nr)
{
    switch (nr) {
    case 0x02: return "IOCTL_KGSL_DEVICE_GETPROPERTY";
    case 0x06: return "IOCTL_KGSL_DEVICE_WAITTIMESTAMP";
    case 0x07: return "IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID";
    case 0x10: return "IOCTL_KGSL_RINGBUFFER_ISSUEIBCMDS";
    case 0x11: return "IOCTL_KGSL_CMDSTREAM_READTIMESTAMP";
    case 0x12: return "IOCTL_KGSL_CMDSTREAM_FREEMEMONTIMESTAMP";
    case 0x13: return "IOCTL_KGSL_DRAWCTXT_CREATE";
    case 0x14: return "IOCTL_KGSL_DRAWCTXT_DESTROY";
    case 0x21: return "IOCTL_KGSL_SHAREDMEM_FREE";
    case 0x2f: return "IOCTL_KGSL_GPUMEM_ALLOC";
    case 0x31: return "IOCTL_KGSL_TIMESTAMP_EVENT_OLD";
    case 0x33: return "IOCTL_KGSL_TIMESTAMP_EVENT";
    case 0x34: return "IOCTL_KGSL_GPUMEM_ALLOC_ID";
    case 0x35: return "IOCTL_KGSL_GPUMEM_FREE_ID";
    case 0x36: return "IOCTL_KGSL_GPUMEM_GET_INFO";
    case 0x37: return "IOCTL_KGSL_GPUMEM_SYNC_CACHE";
    case 0x38: return "IOCTL_KGSL_PERFCOUNTER_GET";
    case 0x39: return "IOCTL_KGSL_PERFCOUNTER_PUT";
    case 0x3a: return "IOCTL_KGSL_PERFCOUNTER_QUERY";
    case 0x3b: return "IOCTL_KGSL_PERFCOUNTER_READ";
    case 0x3d: return "IOCTL_KGSL_SUBMIT_COMMANDS";
    case 0x40: return "IOCTL_KGSL_SYNCSOURCE_CREATE";
    case 0x41: return "IOCTL_KGSL_SYNCSOURCE_DESTROY";
    case 0x42: return "IOCTL_KGSL_SYNCSOURCE_CREATE_FENCE";
    case 0x43: return "IOCTL_KGSL_SYNCSOURCE_SIGNAL_FENCE";
    case 0x45: return "IOCTL_KGSL_GPUOBJ_ALLOC";
    case 0x46: return "IOCTL_KGSL_GPUOBJ_FREE";
    case 0x47: return "IOCTL_KGSL_GPUOBJ_INFO";
    case 0x48: return "IOCTL_KGSL_GPUOBJ_IMPORT";
    case 0x49: return "IOCTL_KGSL_GPUOBJ_SYNC";
    case 0x4A: return "IOCTL_KGSL_GPU_COMMAND";
    case 0x4B: return "IOCTL_KGSL_PREEMPTIONCOUNTER_QUERY";
    case 0x4C: return "IOCTL_KGSL_GPUOBJ_SET_INFO";
    default: return NULL;
    }
}

static const char *prop_name(uint32_t t)
{
    switch (t) {
    case 0x01: return "KGSL_PROP_DEVICE_INFO";
    case 0x02: return "KGSL_PROP_DEVICE_SHADOW";
    case 0x03: return "KGSL_PROP_DEVICE_POWER";
    case 0x04: return "KGSL_PROP_SHMEM";
    case 0x05: return "KGSL_PROP_SHMEM_APERTURES";
    case 0x06: return "KGSL_PROP_MMU_ENABLE";
    case 0x07: return "KGSL_PROP_INTERRUPT_WAITS";
    case 0x08: return "KGSL_PROP_VERSION";
    case 0x09: return "KGSL_PROP_GPU_RESET_STAT";
    case 0x10: return "KGSL_PROP_PWRCTRL";
    case 0x12: return "KGSL_PROP_PWR_CONSTRAINT";
    case 0x13: return "KGSL_PROP_UCHE_GMEM_VADDR";
    case 0x14: return "KGSL_PROP_SP_GENERIC_MEM";
    case 0x15: return "KGSL_PROP_UCODE_VERSION";
    case 0x16: return "KGSL_PROP_GPMU_VERSION";
    case 0x17: return "KGSL_PROP_HIGHEST_BANK_BIT";
    case 0x18: return "KGSL_PROP_DEVICE_BITNESS";
    case 0x19: return "KGSL_PROP_DEVICE_QDSS_STM";
    case 0x1a: return "KGSL_PROP_MIN_ACCESS_LENGTH";
    case 0x1b: return "KGSL_PROP_UBWC_MODE";
    case 0x20: return "KGSL_PROP_DEVICE_QTIMER";
    case 0x23: return "KGSL_PROP_SECURE_BUFFER_ALIGNMENT";
    case 0x24: return "KGSL_PROP_SECURE_CTXT_SUPPORT";
    case 0x25: return "KGSL_PROP_SPEED_BIN";
    case 0x26: return "KGSL_PROP_GAMING_BIN";
    case 0x29: return "KGSL_PROP_GPU_MODEL";
    case 0x2a: return "KGSL_PROP_VK_DEVICE_ID";
    case 0x2b: return "KGSL_PROP_IS_LPAC_ENABLED";
    case 0x2c: return "KGSL_PROP_GPU_VA64_SIZE";
    case 0x2d: return "KGSL_PROP_IS_RAYTRACING_ENABLED";
    case 0x2e: return "KGSL_PROP_IS_FASTBLEND_ENABLED";
    case 0x2f: return "KGSL_PROP_UCHE_TRAP_BASE";
    case 0x31: return "KGSL_PROP_GPU_SECURE_VA_SIZE";
    default: return "unknown";
    }
}

struct bo {
    struct bo *next;
    uint32_t id;
    uint64_t size;
    uint64_t gpuaddr;
    void *host;
    size_t host_len;
    int fd;
};

static pthread_mutex_t g_lock = PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP;
static struct bo *g_bos;
static gzFile g_gz;
static int g_enabled;          /* recording on */
static uint64_t g_cap;
static int g_log = -1;         /* log fd, -1 = off */
static uint64_t g_chip_id;
static uint32_t g_gpu_id;
static int g_header_written;
static unsigned char g_kgsl_fd[4096];

/* The real calls go straight to the kernel: no dlsym() during process start,
 * no recursion through malloc/TLS, and bionic's wrappers for these are plain
 * system calls anyway. close() is not wrapped (fdsan owns it). */
static int real_ioctl(int fd, unsigned long req, void *arg)
{
    return (int)syscall(__NR_ioctl, fd, req, arg);
}
static void *real_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
    return (void *)syscall(__NR_mmap, addr, len, prot, flags, fd, off);
}
static int real_munmap(void *addr, size_t len)
{
    return (int)syscall(__NR_munmap, addr, len);
}

/* ---- logging (libwrap format) ---- */
static void logf_(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void logf_(const char *fmt, ...)
{
    if (g_log < 0)
        return;
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0)
        write(g_log, buf, n < (int)sizeof(buf) ? n : (int)sizeof(buf) - 1);
}

static void log_hexdump(const void *data, size_t size)
{
    if (g_log < 0 || !data)
        return;
    const unsigned char *b = data;
    char line[128], alpha[17];
    if (size > 4096)
        size = 4096;
    for (size_t off = 0; off < size; off += 16) {
        int n = snprintf(line, sizeof(line), "\t\t\t%08X", (unsigned)off);
        for (size_t i = 0; i < 16; i++) {
            if (off + i < size) {
                unsigned char c = b[off + i];
                if (!(i % 4))
                    n += snprintf(line + n, sizeof(line) - n, " ");
                n += snprintf(line + n, sizeof(line) - n, " %02x", c);
                alpha[i] = (isprint(c) && c < 0xA0) ? (char)c : '.';
            } else {
                /* libwrap pads short lines without group separators */
                n += snprintf(line + n, sizeof(line) - n, "   ");
                alpha[i] = '.';
            }
        }
        alpha[16] = 0;
        logf_("%s\t|%s|\n", line, alpha);
    }
}

static void log_hexdump_dwords(const uint32_t *d, uint32_t ndw)
{
    if (g_log < 0 || !d)
        return;
    char line[160];
    if (ndw > (1u << 16))
        ndw = 1u << 16;
    for (uint32_t i = 0; i < ndw; i += 8) {
        int n = snprintf(line, sizeof(line), "\t\t\t%08X:   ", i * 4);
        for (uint32_t k = i; k < i + 8 && k < ndw; k++)
            n += snprintf(line + n, sizeof(line) - n, " %08x", d[k]);
        logf_("%s\n", line);
    }
}

static void log_ioctl(int fd, unsigned req, void *arg, int ret, int post)
{
    if (g_log < 0)
        return;
    const char *nm = ioctl_name(_IOC_NR(req));
    char tmp[40];
    if (!nm) {
        snprintf(tmp, sizeof(tmp), "<unknown nr 0x%02x>", _IOC_NR(req));
        nm = tmp;
    }
    unsigned long r = (unsigned long)(long)(int)req;
    if (post)
        logf_("< [%4d] %8s: %s (%08lx) => %d\n", fd, "kgsl-3d", nm, r, ret);
    else
        logf_("> [%4d] %8s: %s (%08lx)\n", fd, "kgsl-3d", nm, r);
    if (arg && (_IOC_DIR(req) & (post ? _IOC_READ : _IOC_WRITE)))
        log_hexdump(arg, _IOC_SIZE(req));
}

/* ---- capture file ---- */
static void finish(void)
{
    pthread_mutex_lock(&g_lock);
    if (g_gz) {
        gzclose(g_gz);
        g_gz = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

static void sect(uint32_t type, const void *data, uint32_t size)
{
    static const uint32_t marker[2] = { 0xffffffffu, 0xffffffffu };
    static const uint8_t pad[4];
    if (!g_gz)
        return;
    uint32_t hdr[2] = { type, (size + 3u) & ~3u };
    gzwrite(g_gz, marker, sizeof(marker));
    gzwrite(g_gz, hdr, sizeof(hdr));
    if (size)
        gzwrite(g_gz, data, size);
    if (hdr[1] - size)
        gzwrite(g_gz, pad, hdr[1] - size);
}

static void write_header(void)
{
    if (g_header_written || !g_chip_id || !g_gz)
        return;
    char cmd[256] = "test";
    int f = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (f >= 0) {
        ssize_t n = read(f, cmd, sizeof(cmd) - 1);
        cmd[n > 0 ? n : 0] = 0;
        close(f);
        const char *base = strrchr(cmd, '/');
        if (base)
            memmove(cmd, base + 1, strlen(base));
    }
    const char *test = getenv("DLREC_TESTNAME");
    if (test)
        sect(RD_TEST, test, strlen(test) + 1);
    sect(RD_CMD, cmd, strlen(cmd) + 1);
    sect(RD_CHIP_ID, &g_chip_id, sizeof(g_chip_id));
    sect(RD_GPU_ID, &g_gpu_id, sizeof(g_gpu_id));
    g_header_written = 1;
}

/* Set up in the library constructor: libc (and malloc) are ready by then, and
 * nothing here may run from inside mmap() during allocator start-up. */
__attribute__((constructor))
static void dlrec_init(void)
{
    /* The CTS prints progress with stdio but never flushes it. */
    setvbuf(stdout, NULL, _IOLBF, 0);

    const char *log = getenv("DLREC_LOG");
    if (log && *log) {
        if (strcmp(log, "-") == 0)
            g_log = 1;
        else
            g_log = open(log, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    }
    const char *cap = getenv("DLREC_BUF_CAP");
    g_cap = cap ? strtoull(cap, NULL, 0) : 0;
    const char *path = getenv("DLREC_PATH");
    if (path && *path) {
        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (fd >= 0) {
            g_gz = gzdopen(fd, "wb1");
            if (g_gz) {
                g_enabled = 1;
                atexit(finish);
                logf_("opened rd, %d\n", fd);
            } else {
                close(fd);
            }
        }
    }
}

/* ---- freedreno test hooks (declared weak in the tests) ---- */
__attribute__((visibility("default")))
void rd_start(const char *name, const char *fmt, ...)
{
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt ? fmt : "", ap);
    va_end(ap);
    pthread_mutex_lock(&g_lock);
    logf_("rd_start: %s: %s\n", name ? name : "", buf);
    if (g_enabled) {
        char t[600];
        snprintf(t, sizeof(t), "%s: %s", name ? name : "", buf);
        sect(RD_TEST, t, strlen(t) + 1);
    }
    pthread_mutex_unlock(&g_lock);
}

__attribute__((visibility("default")))
void rd_end(void)
{
    pthread_mutex_lock(&g_lock);
    if (g_gz)
        gzflush(g_gz, Z_SYNC_FLUSH);
    pthread_mutex_unlock(&g_lock);
}

__attribute__((visibility("default")))
void rd_write_section(enum rd_sect_type type, const void *buf, int sz)
{
    if (sz < 0)
        return;
    pthread_mutex_lock(&g_lock);
    if (g_enabled)
        sect(type, buf, (uint32_t)sz);
    pthread_mutex_unlock(&g_lock);
}

/* ---- fd classification ---- */
static int fd_path(int fd, char *out, size_t len)
{
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, out, len - 1);
    if (n <= 0)
        return 0;
    out[n] = 0;
    return 1;
}

/* Only "is kgsl" is cached, and it is re-checked before use in mmap(),
 * because fds are closed through paths this library does not see. */
static int is_kgsl_fd(int fd, int may_probe)
{
    if (fd < 0 || fd >= (int)sizeof(g_kgsl_fd))
        return 0;
    char target[128];
    if (g_kgsl_fd[fd]) {
        if (may_probe)
            return 1;
        if (fd_path(fd, target, sizeof(target)) && strncmp(target, "/dev/kgsl-3d", 12) == 0)
            return 1;
        g_kgsl_fd[fd] = 0;
        return 0;
    }
    if (!may_probe || !fd_path(fd, target, sizeof(target)))
        return 0;
    if (strncmp(target, "/dev/kgsl-3d", 12) != 0)
        return 0;
    g_kgsl_fd[fd] = 1;
    logf_("found kgsl_3d0: %d\n", fd);
    return 1;
}

/* ---- buffer tracking ---- */
static struct bo *bo_find_id(uint32_t id)
{
    for (struct bo *b = g_bos; b; b = b->next)
        if (b->id == id)
            return b;
    return NULL;
}

static void bo_add(int fd, uint32_t id, uint64_t size, uint64_t gpuaddr)
{
    struct bo *b = bo_find_id(id);
    if (!b) {
        b = calloc(1, sizeof(*b));
        if (!b)
            return;
        b->id = id;
        b->next = g_bos;
        g_bos = b;
    }
    b->fd = fd;
    b->size = size;
    b->gpuaddr = gpuaddr;
}

static void bo_remove(uint32_t id)
{
    for (struct bo **p = &g_bos; *p; p = &(*p)->next) {
        if ((*p)->id == id) {
            struct bo *b = *p;
            *p = b->next;
            free(b);
            return;
        }
    }
}

static void bo_resolve_addr(struct bo *b)
{
    if (b->gpuaddr)
        return;
    struct kgsl_gpuobj_info info = { .id = b->id };
    if (real_ioctl(b->fd, IOCTL_KGSL_GPUOBJ_INFO, &info) == 0) {
        b->gpuaddr = info.gpuaddr;
        if (info.size)
            b->size = info.size;
    }
}

static void dump_submit(struct kgsl_gpu_command *c)
{
    logf_("\t\tdrawctxt_id:\t%08x\n", c->context_id);
    logf_("\t\tflags:\t\t%08x %08x\n", (uint32_t)(c->flags >> 32), (uint32_t)c->flags);
    logf_("\t\tnumcmds:\t\t%08x\n", c->numcmds);
    if (!c->cmdlist || !c->numcmds || c->cmdsize < sizeof(struct kgsl_command_object))
        return;
    if (!g_enabled && g_log < 0)
        return;
    const uint8_t *list = (const uint8_t *)(uintptr_t)c->cmdlist;

    for (struct bo *b = g_bos; b; b = b->next)
        bo_resolve_addr(b);

    for (uint32_t i = 0; i < c->numcmds; i++) {
        const struct kgsl_command_object *o = (const void *)(list + (size_t)i * c->cmdsize);
        logf_("\t\tcmd[%u].flags:\t\t%08x\n", i, o->flags);
        logf_("\t\tcmd[%u].sizedwords:\t%08x\n", i, (uint32_t)(o->size / 4));
        logf_("\t\tcmd[%u].gpuaddr:\t%08llx\n", i, (unsigned long long)o->gpuaddr);
        if (g_log < 0)
            continue;
        for (struct bo *b = g_bos; b; b = b->next) {
            if (b->host && b->gpuaddr && o->gpuaddr >= b->gpuaddr && o->gpuaddr < b->gpuaddr + b->size) {
                uint64_t off = o->gpuaddr - b->gpuaddr;
                uint32_t ndw = (uint32_t)(o->size / 4);
                if (off + (uint64_t)ndw * 4 <= b->host_len) {
                    logf_("\t\tcmd: (%u dwords)\n", ndw);
                    log_hexdump_dwords((const uint32_t *)((const uint8_t *)b->host + off), ndw);
                }
                break;
            }
        }
    }

    if (!g_enabled)
        return;
    write_header();

    /* Buffers that hold this submit's command streams are always written. */
    for (struct bo *b = g_bos; b; b = b->next) {
        if (!b->host || !b->gpuaddr)
            continue;
        int holds_ib = 0;
        for (uint32_t i = 0; i < c->numcmds; i++) {
            const struct kgsl_command_object *o = (const void *)(list + (size_t)i * c->cmdsize);
            if (o->gpuaddr >= b->gpuaddr && o->gpuaddr < b->gpuaddr + b->size)
                holds_ib = 1;
        }
        if (!holds_ib && g_cap && b->size > g_cap)
            continue;
        uint64_t len = b->size < b->host_len ? b->size : b->host_len;
        uint32_t addr[3] = { (uint32_t)b->gpuaddr, (uint32_t)len, (uint32_t)(b->gpuaddr >> 32) };
        sect(RD_GPUADDR, addr, sizeof(addr));
        sect(RD_BUFFER_CONTENTS, b->host, (uint32_t)len);
    }
    for (uint32_t i = 0; i < c->numcmds; i++) {
        const struct kgsl_command_object *o = (const void *)(list + (size_t)i * c->cmdsize);
        uint32_t addr[3] = { (uint32_t)o->gpuaddr, (uint32_t)(o->size / 4), (uint32_t)(o->gpuaddr >> 32) };
        sect(RD_CMDSTREAM_ADDR, addr, sizeof(addr));
    }
    gzflush(g_gz, Z_SYNC_FLUSH);
}

static void on_mmap(void *ret, size_t len, int prot, int flags, int fd, off_t off)
{
    if (!is_kgsl_fd(fd, 0))
        return;
    struct bo *b = bo_find_id((uint32_t)(off >> 12));
    logf_("< [%4d]         : mmap64: addr=%p, length=%u, prot=%x, flags=%x, offset=%08lx\n",
          fd, (void *)0, (unsigned)len, prot, flags, (unsigned long)off);
    logf_("< [%4d]         : mmap64: -> (%p), buf=%p\n", fd, ret, (void *)b);
    if (ret != MAP_FAILED && b) {
        b->host = ret;
        b->host_len = len;
    }
}

/* ---- wrapped functions ---- */
__attribute__((visibility("default")))
int ioctl(int fd, int request, ...)
{
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    unsigned req = (unsigned)request;
    if (!g_enabled && g_log < 0)
        return real_ioctl(fd, req, arg);

    pthread_mutex_lock(&g_lock);
    int kgsl = _IOC_TYPE(req) == KGSL_IOC_TYPE && is_kgsl_fd(fd, 1);
    if (!kgsl) {
        if (g_log >= 0) {
            char target[128];
            if (fd_path(fd, target, sizeof(target)) && strstr(target, "/dev/"))
                logf_("#### missing device, path: %s: %d\n", target, fd);
            logf_("> [%4d]         : <unknown> (%08lx)\n", fd, (unsigned long)(long)(int)req);
        }
        pthread_mutex_unlock(&g_lock);
        int r = real_ioctl(fd, req, arg);
        if (g_log >= 0)
            logf_("< [%4d]         : <unknown> (%08lx) (%d)\n", fd, (unsigned long)(long)(int)req, r);
        return r;
    }

    log_ioctl(fd, req, arg, 0, 0);
    if (arg) {
        switch (req) {
        case IOCTL_KGSL_GPU_COMMAND:
            dump_submit(arg);
            break;
        case IOCTL_KGSL_GPUOBJ_ALLOC: {
            struct kgsl_gpuobj_alloc *p = arg;
            logf_("\t\tflags:\t\t%08x %08x\n", (uint32_t)(p->flags >> 32), (uint32_t)p->flags);
            logf_("\t\tsize:\t\t%08x\n", (uint32_t)p->size);
            break;
        }
        case IOCTL_KGSL_GPUOBJ_INFO:
            logf_("\t\tid:\t%u\n", ((struct kgsl_gpuobj_info *)arg)->id);
            break;
        case IOCTL_KGSL_GPUOBJ_FREE:
            logf_("\t\tid:\t%u\n", ((struct kgsl_gpuobj_free *)arg)->id);
            break;
        case IOCTL_KGSL_DRAWCTXT_CREATE:
            logf_("\t\tflags:\t\t%08x\n", ((struct kgsl_drawctxt_create *)arg)->flags);
            break;
        default:
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);

    int ret = real_ioctl(fd, req, arg);

    pthread_mutex_lock(&g_lock);
    log_ioctl(fd, req, arg, ret, 1);
    if (ret == 0 && arg) {
        switch (req) {
        case IOCTL_KGSL_DEVICE_GETPROPERTY: {
            struct kgsl_device_getproperty *p = arg;
            logf_("\t\ttype:\t\t%08x (%s)\n", p->type, prop_name(p->type));
            if (p->type == KGSL_PROP_DEVICE_INFO && p->value && p->sizebytes >= sizeof(struct kgsl_devinfo)) {
                struct kgsl_devinfo *d = p->value;
                uint32_t gpu_id = d->gpu_id;
                if (!gpu_id)
                    gpu_id = ((d->chip_id >> 24) & 0xff) * 100 + ((d->chip_id >> 16) & 0xff) * 10 +
                             ((d->chip_id >> 8) & 0xff);
                g_chip_id = d->chip_id;
                g_gpu_id = gpu_id;
                logf_("\t\tgpu_id: %u\n", gpu_id);
                logf_("\t\tgmem_sizebytes: 0x%x\n", (uint32_t)d->gmem_sizebytes);
            }
            if (p->value && p->sizebytes)
                log_hexdump(p->value, p->sizebytes);
            break;
        }
        case IOCTL_KGSL_GPUOBJ_ALLOC: {
            struct kgsl_gpuobj_alloc *p = arg;
            logf_("\t\tid:\t%u\n", p->id);
            bo_add(fd, p->id, p->size, 0);
            break;
        }
        case IOCTL_KGSL_GPUMEM_ALLOC_ID: {
            struct kgsl_gpumem_alloc_id *p = arg;
            logf_("\t\tid:\t%u\n\t\tgpuaddr:\t%08lx\n", p->id, p->gpuaddr);
            bo_add(fd, p->id, p->size, p->gpuaddr);
            break;
        }
        case IOCTL_KGSL_GPUOBJ_INFO: {
            struct kgsl_gpuobj_info *p = arg;
            struct bo *b = bo_find_id(p->id);
            if (b && p->gpuaddr)
                b->gpuaddr = p->gpuaddr;
            logf_("\t\tid:\t%u\n\t\tgpuaddr:\t%08llx\n", p->id, (unsigned long long)p->gpuaddr);
            break;
        }
        case IOCTL_KGSL_GPUOBJ_FREE:
            bo_remove(((struct kgsl_gpuobj_free *)arg)->id);
            break;
        case IOCTL_KGSL_GPUMEM_FREE_ID:
            bo_remove(((struct kgsl_gpumem_free_id *)arg)->id);
            break;
        case IOCTL_KGSL_DRAWCTXT_CREATE:
            logf_("\t\tdrawctxt_id:\t%08x\n", ((struct kgsl_drawctxt_create *)arg)->drawctxt_id);
            break;
        case IOCTL_KGSL_GPU_COMMAND:
            logf_("\t\ttimestamp:\t%08x\n", ((struct kgsl_gpu_command *)arg)->timestamp);
            break;
        default:
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return ret;
}

__attribute__((visibility("default")))
void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
    void *ret = real_mmap(addr, len, prot, flags, fd, off);
    if (fd >= 0 && (g_enabled || g_log >= 0)) {
        pthread_mutex_lock(&g_lock);
        on_mmap(ret, len, prot, flags, fd, off);
        pthread_mutex_unlock(&g_lock);
    }
    return ret;
}

__attribute__((visibility("default")))
void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off64_t off)
{
    return mmap(addr, len, prot, flags, fd, (off_t)off);
}

__attribute__((visibility("default")))
int munmap(void *addr, size_t len)
{
    if (g_enabled || g_log >= 0) {
        pthread_mutex_lock(&g_lock);
        for (struct bo *b = g_bos; b; b = b->next) {
            if (b->host && (uint8_t *)b->host >= (uint8_t *)addr &&
                (uint8_t *)b->host < (uint8_t *)addr + len) {
                b->host = NULL;
                b->host_len = 0;
            }
        }
        pthread_mutex_unlock(&g_lock);
    }
    return real_munmap(addr, len);
}
