// SPDX-License-Identifier: MIT
// DriverLab Vulkan shim: lets the Vulkan CTS load a custom driver .so
// directly (no system loader). Path comes from DRIVERLAB_VK_DRIVER.
// Supports Android HAL drivers (export "HMI", e.g. Turnip AdrenoTools zips)
// and loader-ICD style drivers (export vk_icdGetInstanceProcAddr).
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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

static void shim_init(void)
{
    const char *path = getenv("DRIVERLAB_VK_DRIVER");
    if (!path || !*path) {
        shim_log("DRIVERLAB_VK_DRIVER not set", NULL);
        return;
    }
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
