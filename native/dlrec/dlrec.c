// SPDX-License-Identifier: MIT
// DriverLab recorder (libdlrec.so), loaded with LD_PRELOAD into a test
// process that uses the system (KGSL) Vulkan/GLES driver. It watches the
// KGSL ioctls and writes every GPU submit as a freedreno ".rd" capture
// (gzip), the format Mesa's cffdump and DriverLab read.
//
// Unlike the original freedreno libwrap (Rob Clark, which this follows for
// the file format), it never changes the driver's requests: buffer flags are
// left alone and GPU addresses are looked up with GPUOBJ_INFO when needed.
//
// It also makes stdout line-buffered in every process it is loaded into.
//
// Environment:
//   DLREC_PATH     output file (.rd.gz); recording is off when unset
//   DLREC_BUF_CAP  skip buffers larger than this many bytes unless they hold
//                  the command stream being submitted (0 = record all)
//   DLREC_LOG      optional text log of KGSL activity
#define BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD 1
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
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
#define IOCTL_KGSL_GPUMEM_ALLOC_ID    _IOWR(KGSL_IOC_TYPE, 0x34, struct kgsl_gpumem_alloc_id)
#define IOCTL_KGSL_GPUMEM_FREE_ID     _IOWR(KGSL_IOC_TYPE, 0x35, struct kgsl_gpumem_free_id)
#define IOCTL_KGSL_GPUOBJ_ALLOC       _IOWR(KGSL_IOC_TYPE, 0x45, struct kgsl_gpuobj_alloc)
#define IOCTL_KGSL_GPUOBJ_FREE        _IOW(KGSL_IOC_TYPE, 0x46, struct kgsl_gpuobj_free)
#define IOCTL_KGSL_GPUOBJ_INFO        _IOWR(KGSL_IOC_TYPE, 0x47, struct kgsl_gpuobj_info)
#define IOCTL_KGSL_GPU_COMMAND        _IOWR(KGSL_IOC_TYPE, 0x4A, struct kgsl_gpu_command)

/* Names for the per-test log (same style as freedreno libwrap logs). */
static const char *ioctl_name(unsigned nr)
{
    switch (nr) {
    case 0x02: return "IOCTL_KGSL_DEVICE_GETPROPERTY";
    case 0x07: return "IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID";
    case 0x13: return "IOCTL_KGSL_DRAWCTXT_CREATE";
    case 0x14: return "IOCTL_KGSL_DRAWCTXT_DESTROY";
    case 0x33: return "IOCTL_KGSL_TIMESTAMP_EVENT";
    case 0x34: return "IOCTL_KGSL_GPUMEM_ALLOC_ID";
    case 0x35: return "IOCTL_KGSL_GPUMEM_FREE_ID";
    case 0x38: return "IOCTL_KGSL_PERFCOUNTER_GET";
    case 0x39: return "IOCTL_KGSL_PERFCOUNTER_PUT";
    case 0x40: return "IOCTL_KGSL_SYNCSOURCE_CREATE";
    case 0x41: return "IOCTL_KGSL_SYNCSOURCE_DESTROY";
    case 0x45: return "IOCTL_KGSL_GPUOBJ_ALLOC";
    case 0x46: return "IOCTL_KGSL_GPUOBJ_FREE";
    case 0x47: return "IOCTL_KGSL_GPUOBJ_INFO";
    case 0x48: return "IOCTL_KGSL_GPUOBJ_IMPORT";
    case 0x49: return "IOCTL_KGSL_GPUOBJ_SYNC";
    case 0x4A: return "IOCTL_KGSL_GPU_COMMAND";
    default: return NULL;
    }
}

static const char *prop_name(uint32_t t)
{
    switch (t) {
    case 0x01: return "KGSL_PROP_DEVICE_INFO";
    case 0x02: return "KGSL_PROP_DEVICE_SHADOW";
    case 0x13: return "KGSL_PROP_UCHE_GMEM_VADDR";
    case 0x15: return "KGSL_PROP_UCODE_VERSION";
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
    default: return "?";
    }
}

/* ---- freedreno rd format (Mesa src/freedreno/common/redump.h) ---- */
enum { RD_TEST = 1, RD_CMD = 2, RD_GPUADDR = 3, RD_CMDSTREAM_ADDR = 6,
       RD_BUFFER_CONTENTS = 12, RD_GPU_ID = 13, RD_CHIP_ID = 14 };

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
/* The real calls go straight to the kernel: no dlsym() during process start,
 * no recursion through malloc/TLS, and bionic's wrappers for these four are
 * plain system calls anyway. close() is not wrapped (fdsan owns it). */
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

static struct bo *g_bos;
static gzFile g_gz;
static int g_enabled = -1;
static uint64_t g_cap;
static int g_log = -1;
static uint64_t g_chip_id;
static uint32_t g_gpu_id;
static int g_header_written;
static unsigned char g_kgsl_fd[4096];
static unsigned long g_submits;

static void logf_(const char *fmt, ...)
{
    if (g_log < 0)
        return;
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0)
        write(g_log, buf, n < (int)sizeof(buf) ? n : (int)sizeof(buf) - 1);
}

static void log_hex(const void *data, size_t len)
{
    const uint8_t *p = data;
    if (len > 256)
        len = 256;
    for (size_t off = 0; off < len; off += 16) {
        char line[128];
        int n = snprintf(line, sizeof(line), "\t\t\t%08zx ", off);
        for (size_t i = off; i < off + 16 && i < len; i++)
            n += snprintf(line + n, sizeof(line) - n, " %02x", p[i]);
        logf_("%s\n", line);
    }
}

static void log_ioctl(int fd, unsigned req, int ret, int post)
{
    if (g_log < 0)
        return;
    const char *nm = ioctl_name(_IOC_NR(req));
    char tmp[32];
    if (!nm) {
        snprintf(tmp, sizeof(tmp), "KGSL_IOCTL_0x%02x", _IOC_NR(req));
        nm = tmp;
    }
    if (post)
        logf_("< [%4d]  kgsl-3d: %s (%08x) => %d\n", fd, nm, req, ret);
    else
        logf_("> [%4d]  kgsl-3d: %s (%08x)\n", fd, nm, req);
}

static void finish(void)
{
    pthread_mutex_lock(&g_lock);
    if (g_gz) {
        gzclose(g_gz);
        g_gz = NULL;
    }
    logf_("dlrec: done, %lu submits\n", g_submits);
    pthread_mutex_unlock(&g_lock);
}

static int enabled(void)
{
    if (g_enabled >= 0)
        return g_enabled;
    const char *path = getenv("DLREC_PATH");
    const char *cap = getenv("DLREC_BUF_CAP");
    const char *log = getenv("DLREC_LOG");
    g_cap = cap ? strtoull(cap, NULL, 0) : 0;
    if (log && *log)
        g_log = open(log, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    g_enabled = 0;
    if (path && *path) {
        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (fd >= 0) {
            g_gz = gzdopen(fd, "wb1");
            if (g_gz) {
                g_enabled = 1;
                atexit(finish);
            } else {
                close(fd);
            }
        }
        logf_("dlrec: output %s -> %s\n", path, g_enabled ? "ok" : strerror(errno));
    }
    return g_enabled;
}

static void sect(uint32_t type, const void *data, uint32_t size)
{
    static const uint32_t marker[2] = { 0xffffffffu, 0xffffffffu };
    static const uint8_t pad[4];
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
    if (g_header_written || !g_chip_id)
        return;
    char cmd[256] = "deqp";
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

/* The CTS prints progress with stdio but never flushes it, so through a pipe
 * its "Test case ..." lines arrive in blocks. DriverLab preloads this library
 * into every CTS run (recording or not) to make stdout line-buffered. */
__attribute__((constructor))
static void dlrec_line_buffered_stdout(void)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
}

/* Only "is kgsl" is cached, and it is re-checked before use in mmap(),
 * because fds are closed through paths this library does not see. */
static int fd_is_kgsl_now(int fd)
{
    char path[64], target[128];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, target, sizeof(target) - 1);
    if (n <= 0)
        return 0;
    target[n] = 0;
    return strncmp(target, "/dev/kgsl-3d", 12) == 0;
}

static int is_kgsl_fd(int fd, int may_probe)
{
    if (fd < 0 || fd >= (int)sizeof(g_kgsl_fd))
        return 0;
    if (g_kgsl_fd[fd]) {
        if (may_probe)
            return 1;
        if (fd_is_kgsl_now(fd))
            return 1;
        g_kgsl_fd[fd] = 0;
        return 0;
    }
    if (!may_probe)
        return 0;
    char path[64], target[128];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, target, sizeof(target) - 1);
    if (n <= 0)
        return 0;
    target[n] = 0;
    if (strncmp(target, "/dev/kgsl-3d", 12) != 0)
        return 0;
    g_kgsl_fd[fd] = 1;
    return 1;
}

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
    if (!c->cmdlist || !c->numcmds || c->cmdsize < sizeof(struct kgsl_command_object))
        return;
    write_header();

    for (struct bo *b = g_bos; b; b = b->next)
        bo_resolve_addr(b);

    /* Buffers that hold this submit's command streams are always written. */
    const uint8_t *list = (const uint8_t *)(uintptr_t)c->cmdlist;
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
    g_submits++;
    logf_("\t\tsubmit %lu: drawctxt_id %u, numcmds %u\n", g_submits, c->context_id, c->numcmds);
    for (uint32_t i = 0; i < c->numcmds; i++) {
        const struct kgsl_command_object *o = (const void *)(list + (size_t)i * c->cmdsize);
        logf_("\t\tcmd[%u]: gpuaddr 0x%llx, %llu dwords, flags 0x%x\n", i,
              (unsigned long long)o->gpuaddr, (unsigned long long)(o->size / 4), o->flags);
    }
}

static void on_mmap(void *ret, size_t len, int fd, off_t off)
{
    if (ret == MAP_FAILED || !is_kgsl_fd(fd, 0))
        return;
    struct bo *b = bo_find_id((uint32_t)(off >> 12));
    if (b) {
        b->host = ret;
        b->host_len = len;
    }
}

__attribute__((visibility("default")))
int ioctl(int fd, int request, ...)
{
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    unsigned req = (unsigned)request;
    if (_IOC_TYPE(req) != KGSL_IOC_TYPE || !enabled())
        return real_ioctl(fd, req, arg);

    pthread_mutex_lock(&g_lock);
    int kgsl = is_kgsl_fd(fd, 1);
    if (kgsl)
        log_ioctl(fd, req, 0, 0);
    if (kgsl && req == IOCTL_KGSL_GPU_COMMAND && arg)
        dump_submit(arg);
    pthread_mutex_unlock(&g_lock);

    int ret = real_ioctl(fd, req, arg);
    if (!kgsl)
        return ret;
    pthread_mutex_lock(&g_lock);
    log_ioctl(fd, req, ret, 1);
    pthread_mutex_unlock(&g_lock);
    if (ret != 0 || !arg)
        return ret;

    pthread_mutex_lock(&g_lock);
    switch (req) {
    case IOCTL_KGSL_DEVICE_GETPROPERTY: {
        struct kgsl_device_getproperty *p = arg;
        logf_("\t\ttype:\t\t%08x (%s)\n", p->type, prop_name(p->type));
        if (p->value && p->sizebytes)
            log_hex(p->value, p->sizebytes);
        if (p->type == KGSL_PROP_DEVICE_INFO && p->value && p->sizebytes >= sizeof(struct kgsl_devinfo)) {
            struct kgsl_devinfo *d = p->value;
            g_chip_id = d->chip_id;
            g_gpu_id = d->gpu_id;
            logf_("\t\tgpu_id: %u\n\t\tchip_id: 0x%08x\n\t\tgmem_sizebytes: 0x%zx\n",
                  d->gpu_id, d->chip_id, d->gmem_sizebytes);
        }
        break;
    }
    case IOCTL_KGSL_GPUOBJ_ALLOC: {
        struct kgsl_gpuobj_alloc *p = arg;
        bo_add(fd, p->id, p->size, 0);
        break;
    }
    case IOCTL_KGSL_GPUMEM_ALLOC_ID: {
        struct kgsl_gpumem_alloc_id *p = arg;
        bo_add(fd, p->id, p->size, p->gpuaddr);
        break;
    }
    case IOCTL_KGSL_GPUOBJ_INFO: {
        struct kgsl_gpuobj_info *p = arg;
        struct bo *b = bo_find_id(p->id);
        if (b && p->gpuaddr)
            b->gpuaddr = p->gpuaddr;
        break;
    }
    case IOCTL_KGSL_GPUOBJ_FREE:
        bo_remove(((struct kgsl_gpuobj_free *)arg)->id);
        break;
    case IOCTL_KGSL_GPUMEM_FREE_ID:
        bo_remove(((struct kgsl_gpumem_free_id *)arg)->id);
        break;
    default:
        break;
    }
    pthread_mutex_unlock(&g_lock);
    return ret;
}

__attribute__((visibility("default")))
void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
    void *ret = real_mmap(addr, len, prot, flags, fd, off);
    /* Never initialise from mmap(): the allocator calls mmap() while it sets
     * itself up, and malloc() from there hands out untagged pointers that
     * abort at free(). The first KGSL ioctl always comes before any KGSL mmap. */
    if (fd >= 0 && g_enabled == 1) {
        pthread_mutex_lock(&g_lock);
        on_mmap(ret, len, fd, off);
        pthread_mutex_unlock(&g_lock);
    }
    return ret;
}

__attribute__((visibility("default")))
void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off64_t off)
{
    void *ret = real_mmap(addr, len, prot, flags, fd, (off_t)off);
    /* Never initialise from mmap(): the allocator calls mmap() while it sets
     * itself up, and malloc() from there hands out untagged pointers that
     * abort at free(). The first KGSL ioctl always comes before any KGSL mmap. */
    if (fd >= 0 && g_enabled == 1) {
        pthread_mutex_lock(&g_lock);
        on_mmap(ret, len, fd, off);
        pthread_mutex_unlock(&g_lock);
    }
    return ret;
}

__attribute__((visibility("default")))
int munmap(void *addr, size_t len)
{
    if (g_enabled == 1) {
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
