// SPDX-License-Identifier: MIT
// DriverLab Vulkan shim: lets the Vulkan CTS load a custom driver .so
// directly (no system loader). Path comes from DRIVERLAB_VK_DRIVER.
// Supports Android HAL drivers (export "HMI", e.g. Turnip AdrenoTools zips)
// and loader-ICD style drivers (export vk_icdGetInstanceProcAddr).
//
// Drivers taken from phones (Qualcomm's own) come with the libraries they
// need (libgsl.so, libllvm-glnext.so, ...). Before the driver is opened, its
// needed libraries that sit in its folder (or in DRIVERLAB_VK_LIBDIRS,
// colon-separated) are opened from there, deepest first, so the linker finds
// them by name; the other shipped .so files follow, for the ones the driver
// opens by name at run time. LD_LIBRARY_PATH is not used: it would also swap the CTS's own
// system libraries for the driver's copies. A copy that can't load on this
// phone (built for a newer Android) is skipped and the phone's copy is used.
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Minimal Vulkan types so the shim builds without Vulkan headers. */
#define VKAPI_ATTR
#define VKAPI_CALL
#define VKAPI_PTR
typedef struct VkInstance_T *VkInstance;
typedef int32_t VkResult;
typedef void (VKAPI_PTR *PFN_vkVoidFunction)(void);
typedef PFN_vkVoidFunction (VKAPI_PTR *PFN_vkGetInstanceProcAddr)(VkInstance, const char *);
typedef void *PFN_vkEnumerateInstanceExtensionProperties;
typedef void *PFN_vkCreateInstance;

#define HARDWARE_MODULE_TAG 0x48574d54u /* 'HWMT' */
#define HWVULKAN_DEVICE_0 "vk0"

struct hw_module_t;
struct hw_device_t;
typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t *module, const char *id, struct hw_device_t **device);
} hw_module_methods_t;
typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    hw_module_methods_t *methods;
    void *dso;
    uint64_t reserved[32 - 7];
} hw_module_t;
typedef struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t *module;
    uint64_t reserved[12];
    int (*close)(struct hw_device_t *device);
} hw_device_t;
typedef struct hwvulkan_device_t {
    hw_device_t common;
    PFN_vkEnumerateInstanceExtensionProperties EnumerateInstanceExtensionProperties;
    PFN_vkCreateInstance CreateInstance;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
} hwvulkan_device_t;

typedef VkResult (VKAPI_PTR *PFN_negotiate)(uint32_t *);

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static PFN_vkGetInstanceProcAddr g_gipa;

static void shim_log(const char *msg, const char *detail)
{
    fprintf(stderr, "DRIVERLAB_SHIM: %s%s%s\n", msg, detail ? ": " : "", detail ? detail : "");
    fflush(stderr);
}

/* ---- the driver's own libraries ---- */

#define MAX_DIRS 16
#define MAX_SEEN 512
#define MAX_NEEDED 128

static char g_dirs[MAX_DIRS][PATH_MAX];
static int g_ndirs;
static char g_seen[MAX_SEEN][256];
static int g_nseen;
static int g_own, g_phone;

typedef struct {
    unsigned char ident[16];
    uint16_t type, machine;
    uint32_t version;
    uint64_t entry, phoff, shoff;
    uint32_t flags;
    uint16_t ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
} elf64_ehdr;
typedef struct {
    uint32_t type, flags;
    uint64_t offset, vaddr, paddr, filesz, memsz, align;
} elf64_phdr;
typedef struct {
    int64_t tag;
    uint64_t val;
} elf64_dyn;

static int read_at(int fd, void *buf, size_t n, uint64_t off)
{
    return pread(fd, buf, n, (off_t)off) == (ssize_t)n ? 0 : -1;
}

/* DT_NEEDED names of an ELF64 file into out[]; returns the count (0 on any problem). */
static int read_needed(const char *path, char out[][256], int max)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return 0;
    int n = 0;
    elf64_ehdr eh;
    elf64_phdr ph[64];
    elf64_dyn dyn[512];
    char *strtab = NULL;
    if (read_at(fd, &eh, sizeof eh, 0) || memcmp(eh.ident, "\177ELF", 4) || eh.ident[4] != 2 ||
        eh.phentsize != sizeof(elf64_phdr) || eh.phnum == 0 || eh.phnum > 64 ||
        read_at(fd, ph, sizeof(elf64_phdr) * eh.phnum, eh.phoff))
        goto done;
    const elf64_phdr *dp = NULL;
    for (int i = 0; i < eh.phnum; i++)
        if (ph[i].type == 2 /* PT_DYNAMIC */)
            dp = &ph[i];
    if (!dp)
        goto done;
    size_t ndyn = dp->filesz / sizeof(elf64_dyn);
    if (ndyn > 512)
        ndyn = 512;
    if (read_at(fd, dyn, ndyn * sizeof(elf64_dyn), dp->offset))
        goto done;
    uint64_t str_va = 0, str_sz = 0;
    for (size_t i = 0; i < ndyn && dyn[i].tag != 0; i++) {
        if (dyn[i].tag == 5 /* DT_STRTAB */)
            str_va = dyn[i].val;
        else if (dyn[i].tag == 10 /* DT_STRSZ */)
            str_sz = dyn[i].val;
    }
    if (!str_va || !str_sz || str_sz > (1u << 24))
        goto done;
    uint64_t str_off = 0;
    int found = 0;
    for (int i = 0; i < eh.phnum; i++)
        if (ph[i].type == 1 /* PT_LOAD */ && str_va >= ph[i].vaddr && str_va + str_sz <= ph[i].vaddr + ph[i].filesz) {
            str_off = str_va - ph[i].vaddr + ph[i].offset;
            found = 1;
            break;
        }
    if (!found || !(strtab = malloc(str_sz + 1)) || read_at(fd, strtab, str_sz, str_off))
        goto done;
    strtab[str_sz] = 0;
    for (size_t i = 0; i < ndyn && dyn[i].tag != 0 && n < max; i++)
        if (dyn[i].tag == 1 /* DT_NEEDED */ && dyn[i].val < str_sz) {
            const char *s = strtab + dyn[i].val;
            if (!strchr(s, '/') && strlen(s) < 256)
                strcpy(out[n++], s);
        }
done:
    free(strtab);
    close(fd);
    return n;
}

static int find_own(const char *name, char *path)
{
    for (int i = 0; i < g_ndirs; i++) {
        if (snprintf(path, PATH_MAX, "%s/%s", g_dirs[i], name) >= PATH_MAX)
            continue;
        if (access(path, R_OK) == 0)
            return 1;
    }
    return 0;
}

/* Opens the driver's own copy of `name` after its own needed libraries. */
static void open_own(const char *name, int depth)
{
    for (int i = 0; i < g_nseen; i++)
        if (strcmp(g_seen[i], name) == 0)
            return;
    if (g_nseen >= MAX_SEEN || depth > 32)
        return;
    strcpy(g_seen[g_nseen++], name);

    char path[PATH_MAX];
    if (!find_own(name, path))
        return; /* not shipped with the driver: the linker uses the phone's */
    void *already = dlopen(name, RTLD_NOW | RTLD_NOLOAD);
    if (already) {
        shim_log("already loaded, using the phone's copy of", name);
        return;
    }
    char (*needed)[256] = malloc(sizeof(char[MAX_NEEDED][256]));
    if (!needed)
        return;
    int n = read_needed(path, needed, MAX_NEEDED);
    for (int i = 0; i < n; i++)
        open_own(needed[i], depth + 1);
    free(needed);
    if (dlopen(path, RTLD_NOW | RTLD_LOCAL)) {
        g_own++;
    } else {
        g_phone++;
        char msg[PATH_MAX + 64];
        snprintf(msg, sizeof msg, "driver's copy of %s doesn't load here (the phone's own is used if it has one)", name);
        shim_log(msg, dlerror());
    }
}

static void open_driver_libs(const char *driver)
{
    const char *slash = strrchr(driver, '/');
    if (slash && slash != driver && (size_t)(slash - driver) < PATH_MAX) {
        memcpy(g_dirs[0], driver, (size_t)(slash - driver));
        g_dirs[0][slash - driver] = 0;
        g_ndirs = 1;
    }
    const char *extra = getenv("DRIVERLAB_VK_LIBDIRS");
    while (extra && *extra && g_ndirs < MAX_DIRS) {
        const char *end = strchr(extra, ':');
        size_t len = end ? (size_t)(end - extra) : strlen(extra);
        if (len > 0 && len < PATH_MAX) {
            memcpy(g_dirs[g_ndirs], extra, len);
            g_dirs[g_ndirs++][len] = 0;
        }
        extra = end ? end + 1 : NULL;
    }
    if (g_ndirs == 0)
        return;
    char (*needed)[256] = malloc(sizeof(char[MAX_NEEDED][256]));
    if (!needed)
        return;
    int n = read_needed(driver, needed, MAX_NEEDED);
    for (int i = 0; i < n; i++)
        open_own(needed[i], 1);
    free(needed);
    /* Libraries the driver opens by name while running (libllvm-qgl.so,
     * libadreno_app_profiles.so, ...) are not in its needed list; open the
     * rest of the shipped .so files too, so those names resolve to them. */
    const char *self = slash ? slash + 1 : driver;
    for (int d = 0; d < g_ndirs; d++) {
        struct dirent **list = NULL;
        int count = scandir(g_dirs[d], &list, NULL, alphasort);
        for (int i = 0; i < count; i++) {
            const char *nm = list[i]->d_name;
            size_t len = strlen(nm);
            if (len > 3 && strcmp(nm + len - 3, ".so") == 0 && !(d == 0 && strcmp(nm, self) == 0))
                open_own(nm, 1);
            free(list[i]);
        }
        free(list);
    }
    if (g_own || g_phone) {
        char msg[96];
        snprintf(msg, sizeof msg, "opened %d of the driver's own libraries, %d could not load", g_own, g_phone);
        shim_log(msg, NULL);
    }
}

static void shim_init(void)
{
    const char *path = getenv("DRIVERLAB_VK_DRIVER");
    if (!path || !*path) {
        shim_log("DRIVERLAB_VK_DRIVER not set", NULL);
        return;
    }
    open_driver_libs(path);
    void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        shim_log("dlopen failed", dlerror());
        return;
    }
    PFN_vkGetInstanceProcAddr icd = (PFN_vkGetInstanceProcAddr)dlsym(h, "vk_icdGetInstanceProcAddr");
    if (icd) {
        PFN_negotiate neg = (PFN_negotiate)dlsym(h, "vk_icdNegotiateLoaderICDInterfaceVersion");
        if (neg) {
            uint32_t v = 5;
            neg(&v);
        }
        g_gipa = icd;
        shim_log("loaded ICD driver", path);
        return;
    }
    hw_module_t *hmi = (hw_module_t *)dlsym(h, "HMI");
    if (!hmi) {
        shim_log("driver exports neither HMI nor vk_icdGetInstanceProcAddr", path);
        return;
    }
    if (hmi->tag != HARDWARE_MODULE_TAG || !hmi->methods || !hmi->methods->open) {
        shim_log("HMI is not a valid hw_module_t", path);
        return;
    }
    hw_device_t *dev = NULL;
    int rc = hmi->methods->open(hmi, HWVULKAN_DEVICE_0, &dev);
    if (rc != 0 || !dev) {
        shim_log("HAL open(vk0) failed", path);
        return;
    }
    g_gipa = ((hwvulkan_device_t *)dev)->GetInstanceProcAddr;
    shim_log("loaded HAL driver", hmi->name ? hmi->name : path);
}

__attribute__((visibility("default")))
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name)
{
    pthread_once(&g_once, shim_init);
    if (!g_gipa)
        return NULL;
    if (name && strcmp(name, "vkGetInstanceProcAddr") == 0)
        return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    return g_gipa(instance, name);
}
