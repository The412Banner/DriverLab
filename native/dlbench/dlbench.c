// SPDX-License-Identifier: MIT
// DriverLab speed test: the same small Vulkan scenes on any driver, headless
// (offscreen images, no window). Loads the system Vulkan loader, or
// libdlvkshim.so for an imported driver (--vk-lib), and prints one
// "DLBENCH ..." line per event for the app to read.
//
// A frame is: record (scenes that change every frame), submit, wait for the
// fence. Frame time is wall time around that; GPU time comes from timestamps
// when the queue has them. Warm-up frames and preview copies are not counted.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <dlfcn.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "shaders.h"

/* ---- Vulkan functions, loaded at run time ---- */

#define FUNCS_INSTANCE(X) \
    X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties) \
    X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceMemoryProperties) \
    X(vkGetPhysicalDeviceFormatProperties) X(vkCreateDevice) X(vkGetDeviceProcAddr)

#define FUNCS_DEVICE(X) \
    X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkQueueSubmit) X(vkDeviceWaitIdle) \
    X(vkAllocateMemory) X(vkFreeMemory) X(vkMapMemory) X(vkBindBufferMemory) X(vkBindImageMemory) \
    X(vkGetBufferMemoryRequirements) X(vkGetImageMemoryRequirements) \
    X(vkCreateBuffer) X(vkDestroyBuffer) X(vkCreateImage) X(vkDestroyImage) \
    X(vkCreateImageView) X(vkDestroyImageView) X(vkCreateSampler) X(vkDestroySampler) \
    X(vkCreateShaderModule) X(vkDestroyShaderModule) X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) \
    X(vkCreateGraphicsPipelines) X(vkCreateComputePipelines) X(vkDestroyPipeline) \
    X(vkCreateDescriptorSetLayout) X(vkDestroyDescriptorSetLayout) X(vkCreateDescriptorPool) \
    X(vkDestroyDescriptorPool) X(vkResetDescriptorPool) X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) \
    X(vkCreateRenderPass) X(vkDestroyRenderPass) X(vkCreateFramebuffer) X(vkDestroyFramebuffer) \
    X(vkCreateCommandPool) X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) \
    X(vkBeginCommandBuffer) X(vkEndCommandBuffer) X(vkResetCommandBuffer) \
    X(vkCreateFence) X(vkDestroyFence) X(vkWaitForFences) X(vkResetFences) \
    X(vkCreateQueryPool) X(vkDestroyQueryPool) X(vkGetQueryPoolResults) \
    X(vkCmdResetQueryPool) X(vkCmdWriteTimestamp) X(vkCmdBeginRenderPass) X(vkCmdEndRenderPass) \
    X(vkCmdBindPipeline) X(vkCmdBindDescriptorSets) X(vkCmdBindVertexBuffers) X(vkCmdBindIndexBuffer) \
    X(vkCmdDraw) X(vkCmdDrawIndexed) X(vkCmdDispatch) X(vkCmdPushConstants) X(vkCmdPipelineBarrier) \
    X(vkCmdCopyBuffer) X(vkCmdCopyBufferToImage) X(vkCmdCopyImageToBuffer) X(vkCmdBlitImage)

#define DECLARE(f) static PFN_##f f;
static PFN_vkGetInstanceProcAddr gipa;
static PFN_vkCreateInstance vkCreateInstance;
FUNCS_INSTANCE(DECLARE)
FUNCS_DEVICE(DECLARE)

/* ---- state shared by all scenes ---- */

#define MAX_FRAMES 100000
#define STAGING_SIZE (8u << 20)
#define FENCE_TIMEOUT_NS 10000000000ull

static struct {
    VkInstance instance;
    VkPhysicalDevice phys;
    VkDevice device;
    VkQueue queue;
    uint32_t qfam;
    VkPhysicalDeviceProperties props;
    VkPhysicalDeviceMemoryProperties mem;
    uint32_t ts_bits;
    uint32_t width, height;
    VkFormat depth_format;
    VkImageAspectFlags depth_aspect;
    VkImage color, depth;
    VkDeviceMemory color_mem, depth_mem;
    VkImageView color_view, depth_view;
    VkRenderPass rp_cd, rp_c_clear, rp_c_load;
    VkFramebuffer fb_cd, fb_c;
    VkCommandPool pool;
    VkCommandBuffer util_cb, frame_cb;
    VkFence fence;
    VkQueryPool queries;
    VkSampler sampler;
    VkDescriptorSetLayout dsl_tex, dsl_comp;
    VkPipelineLayout pl_plain, pl_tex, pl_comp;
    VkDescriptorPool dpool;
    VkBuffer staging, readback;
    VkDeviceMemory staging_mem, readback_mem;
    uint8_t *staging_map, *readback_map;
    const char *preview_path;
    uint32_t preview_w, preview_h;
    VkImage preview;
    VkDeviceMemory preview_mem;
    double seconds, warmup;
    int lost;
    double *times;
} g;

/* ---- output ---- */

static double now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

/* Percent-encodes spaces, '%', '=' and control bytes so a value is one token. */
static const char *enc(const char *s)
{
    static char buf[2][512];
    static int which;
    char *o = buf[which ^= 1];
    size_t n = 0;
    for (; *s && n + 4 < sizeof(buf[0]); s++) {
        unsigned char c = (unsigned char)*s;
        if (c <= ' ' || c == '%' || c == '=' || c >= 0x7f)
            n += (size_t)snprintf(o + n, 4, "%%%02X", c);
        else
            o[n++] = (char)c;
    }
    o[n] = 0;
    return o;
}

static const char *vkres(VkResult r)
{
    switch (r) {
    case VK_SUCCESS: return "VK_SUCCESS";
    case VK_TIMEOUT: return "VK_TIMEOUT (the GPU did not finish within 10 s)";
    case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
    case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
    case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
    case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
    case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
    case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
    case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
    case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
    case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
    default: {
        static char b[32];
        snprintf(b, sizeof(b), "VkResult %d", (int)r);
        return b;
    }
    }
}

/* Prints an error line for a scene; a lost or hung GPU ends the run. */
static int fail(const char *scene, VkResult r, const char *where)
{
    printf("DLBENCH error scene=%s msg=%s in %s\n", scene, vkres(r), where);
    if (r == VK_ERROR_DEVICE_LOST || r == VK_TIMEOUT)
        g.lost = 1;
    return -1;
}

/* ---- helpers ---- */

static int mem_type(uint32_t bits, VkMemoryPropertyFlags want)
{
    for (uint32_t i = 0; i < g.mem.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g.mem.memoryTypes[i].propertyFlags & want) == want)
            return (int)i;
    return -1;
}

static VkResult alloc_bind(VkMemoryRequirements req, VkMemoryPropertyFlags want, VkDeviceMemory *m)
{
    int t = mem_type(req.memoryTypeBits, want);
    if (t < 0 && (want & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
        t = mem_type(req.memoryTypeBits, 0);
    if (t < 0)
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    VkMemoryAllocateInfo ai = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, req.size, (uint32_t)t };
    return vkAllocateMemory(g.device, &ai, NULL, m);
}

static VkResult make_buffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags want,
                            VkBuffer *b, VkDeviceMemory *m, uint8_t **map)
{
    VkBufferCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
    ci.size = size;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult r = vkCreateBuffer(g.device, &ci, NULL, b);
    if (r) return r;
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(g.device, *b, &req);
    if ((r = alloc_bind(req, want, m))) return r;
    if ((r = vkBindBufferMemory(g.device, *b, *m, 0))) return r;
    if (map) return vkMapMemory(g.device, *m, 0, VK_WHOLE_SIZE, 0, (void **)map);
    return VK_SUCCESS;
}

static VkResult make_image(uint32_t w, uint32_t h, VkFormat f, VkImageUsageFlags usage, VkImage *img, VkDeviceMemory *m)
{
    VkImageCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = f;
    ci.extent.width = w;
    ci.extent.height = h;
    ci.extent.depth = 1;
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult r = vkCreateImage(g.device, &ci, NULL, img);
    if (r) return r;
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(g.device, *img, &req);
    if ((r = alloc_bind(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, m))) return r;
    return vkBindImageMemory(g.device, *img, *m, 0);
}

static VkResult make_view(VkImage img, VkFormat f, VkImageAspectFlags aspect, VkImageView *v)
{
    VkImageViewCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
    ci.image = img;
    ci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    ci.format = f;
    ci.subresourceRange.aspectMask = aspect;
    ci.subresourceRange.levelCount = 1;
    ci.subresourceRange.layerCount = 1;
    return vkCreateImageView(g.device, &ci, NULL, v);
}

static void image_barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to,
                          VkPipelineStageFlags ss, VkAccessFlags sa, VkPipelineStageFlags ds, VkAccessFlags da)
{
    VkImageMemoryBarrier b = { .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    b.srcAccessMask = sa;
    b.dstAccessMask = da;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cb, ss, ds, 0, 0, NULL, 0, NULL, 1, &b);
}

static void memory_barrier(VkCommandBuffer cb, VkPipelineStageFlags ss, VkAccessFlags sa,
                           VkPipelineStageFlags ds, VkAccessFlags da)
{
    VkMemoryBarrier b = { VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, sa, da };
    vkCmdPipelineBarrier(cb, ss, ds, 0, 1, &b, 0, NULL, 0, NULL);
}

static VkResult begin(VkCommandBuffer cb, VkCommandBufferUsageFlags flags)
{
    VkResult r = vkResetCommandBuffer(cb, 0);
    if (r) return r;
    VkCommandBufferBeginInfo bi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, flags, NULL };
    return vkBeginCommandBuffer(cb, &bi);
}

static VkResult submit_wait(VkCommandBuffer cb)
{
    VkSubmitInfo si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO };
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    VkResult r = vkQueueSubmit(g.queue, 1, &si, g.fence);
    if (r) return r;
    r = vkWaitForFences(g.device, 1, &g.fence, VK_TRUE, FENCE_TIMEOUT_NS);
    if (r) return r;
    return vkResetFences(g.device, 1, &g.fence);
}

/* Ends the one-off command buffer, runs it and waits. */
static VkResult end_submit(VkCommandBuffer cb)
{
    VkResult r = vkEndCommandBuffer(cb);
    return r ? r : submit_wait(cb);
}

typedef void (*gen_fn)(uint8_t *dst, VkDeviceSize offset, VkDeviceSize n, const void *ctx);

/* Fills a device buffer through the staging buffer; gen writes bytes [offset, offset + n). */
static VkResult upload_buffer(VkBuffer dst, VkDeviceSize size, gen_fn gen, const void *ctx)
{
    for (VkDeviceSize off = 0; off < size; off += STAGING_SIZE) {
        VkDeviceSize n = size - off < STAGING_SIZE ? size - off : STAGING_SIZE;
        gen(g.staging_map, off, n, ctx);
        VkResult r = begin(g.util_cb, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        if (r) return r;
        VkBufferCopy c = { 0, off, n };
        vkCmdCopyBuffer(g.util_cb, g.staging, dst, 1, &c);
        // Makes the copy visible to everything submitted later.
        memory_barrier(g.util_cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                       VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        if ((r = end_submit(g.util_cb))) return r;
    }
    return VK_SUCCESS;
}

/* Uploads an RGBA8 image (rows from gen) and leaves it ready for sampling. */
static VkResult upload_image(VkImage img, uint32_t w, uint32_t h, gen_fn gen, const void *ctx)
{
    uint32_t rows = STAGING_SIZE / (w * 4);
    for (uint32_t y = 0; y < h; y += rows) {
        uint32_t n = h - y < rows ? h - y : rows;
        gen(g.staging_map, (VkDeviceSize)y * w * 4, (VkDeviceSize)n * w * 4, ctx);
        VkResult r = begin(g.util_cb, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        if (r) return r;
        if (y == 0)
            image_barrier(g.util_cb, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                          VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        VkBufferImageCopy c = { 0 };
        c.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        c.imageSubresource.layerCount = 1;
        c.imageOffset.y = (int32_t)y;
        c.imageExtent.width = w;
        c.imageExtent.height = n;
        c.imageExtent.depth = 1;
        vkCmdCopyBufferToImage(g.util_cb, g.staging, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &c);
        if (y + n >= h)
            image_barrier(g.util_cb, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                          VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        else
            memory_barrier(g.util_cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        if ((r = end_submit(g.util_cb))) return r;
    }
    return VK_SUCCESS;
}

/* Copies one pixel of the color image to the host. */
static VkResult read_pixel(uint32_t x, uint32_t y, uint8_t out[4])
{
    VkResult r = begin(g.util_cb, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
    if (r) return r;
    image_barrier(g.util_cb, g.color, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                  VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    VkBufferImageCopy c = { 0 };
    c.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    c.imageSubresource.layerCount = 1;
    c.imageOffset.x = (int32_t)x;
    c.imageOffset.y = (int32_t)y;
    c.imageExtent.width = 1;
    c.imageExtent.height = 1;
    c.imageExtent.depth = 1;
    vkCmdCopyImageToBuffer(g.util_cb, g.color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, g.readback, 1, &c);
    image_barrier(g.util_cb, g.color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                  VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    memory_barrier(g.util_cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                   VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
    if ((r = end_submit(g.util_cb))) return r;
    memcpy(out, g.readback_map, 4);
    return VK_SUCCESS;
}

/* Copies byte ranges of a device buffer to the readback buffer (packed in order). */
static VkResult read_buffer(VkBuffer src, const VkDeviceSize *offsets, uint32_t count, VkDeviceSize each,
                            VkPipelineStageFlags src_stage)
{
    VkResult r = begin(g.util_cb, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
    if (r) return r;
    memory_barrier(g.util_cb, src_stage, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    VkBufferCopy c[8];
    for (uint32_t i = 0; i < count && i < 8; i++) {
        c[i].srcOffset = offsets[i];
        c[i].dstOffset = i * each;
        c[i].size = each;
    }
    vkCmdCopyBuffer(g.util_cb, src, g.readback, count < 8 ? count : 8, c);
    memory_barrier(g.util_cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                   VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
    return end_submit(g.util_cb);
}

/* Shrinks the color image into the preview file: uint32 LE width, height, then RGBA8 rows. */
static VkResult write_preview(void)
{
    VkResult r = begin(g.util_cb, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
    if (r) return r;
    VkCommandBuffer cb = g.util_cb;
    image_barrier(cb, g.color, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                  VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    image_barrier(cb, g.preview, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
    VkImageBlit b = { 0 };
    b.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.srcSubresource.layerCount = 1;
    b.srcOffsets[1].x = (int32_t)g.width;
    b.srcOffsets[1].y = (int32_t)g.height;
    b.srcOffsets[1].z = 1;
    b.dstSubresource = b.srcSubresource;
    b.dstOffsets[1].x = (int32_t)g.preview_w;
    b.dstOffsets[1].y = (int32_t)g.preview_h;
    b.dstOffsets[1].z = 1;
    vkCmdBlitImage(cb, g.color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, g.preview, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   1, &b, VK_FILTER_LINEAR);
    image_barrier(cb, g.preview, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                  VK_ACCESS_TRANSFER_READ_BIT);
    VkBufferImageCopy c = { 0 };
    c.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    c.imageSubresource.layerCount = 1;
    c.imageExtent.width = g.preview_w;
    c.imageExtent.height = g.preview_h;
    c.imageExtent.depth = 1;
    vkCmdCopyImageToBuffer(cb, g.preview, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, g.readback, 1, &c);
    image_barrier(cb, g.color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                  VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    memory_barrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                   VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
    if ((r = end_submit(cb))) return r;

    char tmp[4096];
    snprintf(tmp, sizeof(tmp), "%s.tmp", g.preview_path);
    FILE *f = fopen(tmp, "wb");
    if (!f) return VK_SUCCESS;
    uint32_t hdr[2] = { g.preview_w, g.preview_h };
    size_t bytes = (size_t)g.preview_w * g.preview_h * 4;
    int ok = fwrite(hdr, sizeof(hdr), 1, f) == 1 && fwrite(g.readback_map, bytes, 1, f) == 1;
    if (fclose(f) == 0 && ok)
        rename(tmp, g.preview_path);
    else
        unlink(tmp);
    return VK_SUCCESS;
}

/* ---- pipelines ---- */

static VkResult make_module(const uint32_t *code, size_t size, VkShaderModule *m)
{
    VkShaderModuleCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
    ci.codeSize = size;
    ci.pCode = code;
    return vkCreateShaderModule(g.device, &ci, NULL, m);
}

struct gpipe {
    const uint32_t *vs, *fs;
    size_t vs_size, fs_size;
    VkPipelineLayout layout;
    VkRenderPass rp;
    VkPrimitiveTopology topology;
    int blend_add, depth, vertex_vec2;
    const VkSpecializationInfo *fs_spec;
};

#define SHADER(name) name, sizeof(name)

static VkResult make_gpipe(const struct gpipe *d, VkPipeline *p)
{
    VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
    VkResult r = make_module(d->vs, d->vs_size, &vs);
    if (!r) r = make_module(d->fs, d->fs_size, &fs);
    if (r) goto out;

    VkPipelineShaderStageCreateInfo st[2] = { { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO },
                                              { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO } };
    st[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    st[0].module = vs;
    st[0].pName = "main";
    st[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    st[1].module = fs;
    st[1].pName = "main";
    st[1].pSpecializationInfo = d->fs_spec;

    VkVertexInputBindingDescription vb = { 0, 8, VK_VERTEX_INPUT_RATE_VERTEX };
    VkVertexInputAttributeDescription va = { 0, 0, VK_FORMAT_R32G32_SFLOAT, 0 };
    VkPipelineVertexInputStateCreateInfo vi = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
    if (d->vertex_vec2) {
        vi.vertexBindingDescriptionCount = 1;
        vi.pVertexBindingDescriptions = &vb;
        vi.vertexAttributeDescriptionCount = 1;
        vi.pVertexAttributeDescriptions = &va;
    }
    VkPipelineInputAssemblyStateCreateInfo ia = { .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO };
    ia.topology = d->topology;
    VkViewport vp = { 0, 0, (float)g.width, (float)g.height, 0, 1 };
    VkRect2D sc = { { 0, 0 }, { g.width, g.height } };
    VkPipelineViewportStateCreateInfo vps = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO };
    vps.viewportCount = 1;
    vps.pViewports = &vp;
    vps.scissorCount = 1;
    vps.pScissors = &sc;
    VkPipelineRasterizationStateCreateInfo rs = { .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO };
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms = { .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO };
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineDepthStencilStateCreateInfo ds = { .sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO };
    ds.depthTestEnable = d->depth ? VK_TRUE : VK_FALSE;
    ds.depthWriteEnable = d->depth ? VK_TRUE : VK_FALSE;
    ds.depthCompareOp = VK_COMPARE_OP_LESS;
    VkPipelineColorBlendAttachmentState ba = { 0 };
    ba.colorWriteMask = 0xf;
    if (d->blend_add) {
        ba.blendEnable = VK_TRUE;
        ba.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
        ba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
        ba.colorBlendOp = VK_BLEND_OP_ADD;
        ba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        ba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        ba.alphaBlendOp = VK_BLEND_OP_ADD;
    }
    VkPipelineColorBlendStateCreateInfo cbs = { .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO };
    cbs.attachmentCount = 1;
    cbs.pAttachments = &ba;

    VkGraphicsPipelineCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO };
    ci.stageCount = 2;
    ci.pStages = st;
    ci.pVertexInputState = &vi;
    ci.pInputAssemblyState = &ia;
    ci.pViewportState = &vps;
    ci.pRasterizationState = &rs;
    ci.pMultisampleState = &ms;
    ci.pDepthStencilState = &ds;
    ci.pColorBlendState = &cbs;
    ci.layout = d->layout;
    ci.renderPass = d->rp;
    r = vkCreateGraphicsPipelines(g.device, VK_NULL_HANDLE, 1, &ci, NULL, p);
out:
    vkDestroyShaderModule(g.device, vs, NULL);
    vkDestroyShaderModule(g.device, fs, NULL);
    return r;
}

static VkResult make_cpipe(const uint32_t *cs, size_t size, VkPipeline *p)
{
    VkShaderModule m;
    VkResult r = make_module(cs, size, &m);
    if (r) return r;
    VkComputePipelineCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
    ci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    ci.stage.module = m;
    ci.stage.pName = "main";
    ci.layout = g.pl_comp;
    r = vkCreateComputePipelines(g.device, VK_NULL_HANDLE, 1, &ci, NULL, p);
    vkDestroyShaderModule(g.device, m, NULL);
    return r;
}

static VkResult alloc_set(VkDescriptorSetLayout l, VkDescriptorSet *s)
{
    VkDescriptorSetAllocateInfo ai = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, g.dpool, 1, &l };
    return vkAllocateDescriptorSets(g.device, &ai, s);
}

static void set_texture(VkDescriptorSet s, VkImageView v)
{
    VkDescriptorImageInfo ii = { g.sampler, v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
    VkWriteDescriptorSet w = { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
    w.dstSet = s;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &ii;
    vkUpdateDescriptorSets(g.device, 1, &w, 0, NULL);
}

static void set_buffer(VkDescriptorSet s, uint32_t binding, VkBuffer b)
{
    VkDescriptorBufferInfo bi = { b, 0, VK_WHOLE_SIZE };
    VkWriteDescriptorSet w = { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
    w.dstSet = s;
    w.dstBinding = binding;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    w.pBufferInfo = &bi;
    vkUpdateDescriptorSets(g.device, 1, &w, 0, NULL);
}

/* ---- small math (column-major 4x4) ---- */

static void mat_mul(float *r, const float *a, const float *b)
{
    float t[16];
    for (int c = 0; c < 4; c++)
        for (int i = 0; i < 4; i++)
            t[c * 4 + i] = a[0 * 4 + i] * b[c * 4 + 0] + a[1 * 4 + i] * b[c * 4 + 1] +
                           a[2 * 4 + i] * b[c * 4 + 2] + a[3 * 4 + i] * b[c * 4 + 3];
    memcpy(r, t, sizeof(t));
}

/* Perspective for Vulkan clip space (depth 0..1, y down). */
static void perspective(float *m, float fovy, float aspect, float zn, float zf)
{
    float f = 1.0f / tanf(fovy * 0.5f);
    memset(m, 0, 16 * sizeof(float));
    m[0] = f / aspect;
    m[5] = -f;
    m[10] = zf / (zn - zf);
    m[11] = -1.0f;
    m[14] = zn * zf / (zn - zf);
}

static void look_at(float *m, const float e[3], const float c[3])
{
    float f[3] = { c[0] - e[0], c[1] - e[1], c[2] - e[2] };
    float fl = sqrtf(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
    for (int i = 0; i < 3; i++) f[i] /= fl;
    /* s = f x up, up = (0, 1, 0) */
    float s[3] = { -f[2], 0.0f, f[0] };
    float sl = sqrtf(s[0] * s[0] + s[2] * s[2]);
    s[0] /= sl;
    s[2] /= sl;
    float u[3] = { s[1] * f[2] - s[2] * f[1], s[2] * f[0] - s[0] * f[2], s[0] * f[1] - s[1] * f[0] };
    float r[16] = { s[0], u[0], -f[0], 0, s[1], u[1], -f[1], 0, s[2], u[2], -f[2], 0,
                    -(s[0] * e[0] + s[1] * e[1] + s[2] * e[2]), -(u[0] * e[0] + u[1] * e[1] + u[2] * e[2]),
                    f[0] * e[0] + f[1] * e[1] + f[2] * e[2], 1 };
    memcpy(m, r, sizeof(r));
}

static void rotation(float *m, float ay, float ax)
{
    float cy = cosf(ay), sy = sinf(ay), cx = cosf(ax), sx = sinf(ax);
    float ry[16] = { cy, 0, -sy, 0, 0, 1, 0, 0, sy, 0, cy, 0, 0, 0, 0, 1 };
    float rx[16] = { 1, 0, 0, 0, 0, cx, sx, 0, 0, -sx, cx, 0, 0, 0, 0, 1 };
    mat_mul(m, ry, rx);
}

static uint32_t hash32(uint32_t x)
{
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

/* ---- the timed loop ---- */

struct pc48 {
    float color[4], uvxf[4], rect[4];
};

typedef void (*record_fn)(VkCommandBuffer cb, double t, void *ctx);

struct loop {
    const char *scene;
    record_fn record;
    void *ctx;
    int every_frame; /* re-record each frame (animation or CPU-cost scenes) */
    int preview;
};

struct stats {
    int frames;
    double seconds, avg, p50, p99, min, max, gpu;
    int have_gpu;
};

static int cmp_double(const void *a, const void *b)
{
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

static VkResult record_frame(const struct loop *L, double t)
{
    VkCommandBuffer cb = g.frame_cb;
    VkResult r = begin(cb, L->every_frame ? VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT : 0);
    if (r) return r;
    if (g.ts_bits) {
        vkCmdResetQueryPool(cb, g.queries, 0, 2);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, g.queries, 0);
    }
    L->record(cb, t, L->ctx);
    if (g.ts_bits)
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, g.queries, 1);
    return vkEndCommandBuffer(cb);
}

static int run_loop(const struct loop *L, struct stats *st)
{
    VkResult r;
    double start = now();
    if (!L->every_frame && (r = record_frame(L, 0)))
        return fail(L->scene, r, "recording");
    int timed = 0, frames = 0;
    double gpu_sum = 0, timed_start = 0, last_preview = 0;
    for (;;) {
        double t0 = now();
        if (!timed && t0 - start >= g.warmup && frames > 0) {
            timed = 1;
            timed_start = t0;
            frames = 0;
        }
        if (timed && frames > 0 && (t0 - timed_start >= g.seconds || frames >= MAX_FRAMES))
            break;
        if (L->every_frame && (r = record_frame(L, t0 - start)))
            return fail(L->scene, r, "recording");
        if ((r = submit_wait(g.frame_cb)))
            return fail(L->scene, r, "running a frame");
        double t1 = now();
        if (timed) {
            g.times[frames] = (t1 - t0) * 1000.0;
            if (g.ts_bits) {
                uint64_t ts[2];
                r = vkGetQueryPoolResults(g.device, g.queries, 0, 2, sizeof(ts), ts, sizeof(uint64_t),
                                          VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                if (r) return fail(L->scene, r, "reading GPU timestamps");
                uint64_t d = ts[1] - ts[0];
                if (g.ts_bits < 64)
                    d &= (1ull << g.ts_bits) - 1;
                gpu_sum += (double)d * g.props.limits.timestampPeriod / 1e6;
            }
        }
        frames++;
        if (L->preview && g.preview_path && t1 - last_preview >= 0.25) {
            if ((r = write_preview()))
                return fail(L->scene, r, "making the preview");
            last_preview = now();
        }
    }
    double end = now();
    if (L->preview && g.preview_path && (r = write_preview()))
        return fail(L->scene, r, "making the preview");

    st->frames = frames;
    st->seconds = end - timed_start;
    double sum = 0;
    for (int i = 0; i < frames; i++) sum += g.times[i];
    st->avg = sum / frames;
    qsort(g.times, (size_t)frames, sizeof(double), cmp_double);
    st->min = g.times[0];
    st->max = g.times[frames - 1];
    st->p50 = g.times[(frames - 1) / 2];
    st->p99 = g.times[(int)((frames - 1) * 0.99)];
    st->have_gpu = g.ts_bits != 0;
    st->gpu = st->have_gpu ? gpu_sum / frames : 0;
    return 0;
}

static void print_result(const char *scene, const struct stats *s, double value, const char *unit,
                         const char *better, const char *check)
{
    char gpu[32];
    if (s->have_gpu)
        snprintf(gpu, sizeof(gpu), "%.3f", s->gpu);
    else
        snprintf(gpu, sizeof(gpu), "-");
    printf("DLBENCH result scene=%s frames=%d seconds=%.3f avg_ms=%.3f p50_ms=%.3f p99_ms=%.3f min_ms=%.3f "
           "max_ms=%.3f gpu_ms=%s value=%.3f unit=%s better=%s check=%s\n",
           scene, s->frames, s->seconds, s->avg, s->p50, s->p99, s->min, s->max, gpu, value, unit, better, check);
}

static void print_frame_result(const char *scene, const struct stats *s, const char *check)
{
    print_result(scene, s, 1000.0 / s->avg, "fps", "higher", check);
}

static void begin_pass(VkCommandBuffer cb, VkRenderPass rp, VkFramebuffer fb, const float clear[4])
{
    VkClearValue cv[2];
    memset(cv, 0, sizeof(cv));
    if (clear) memcpy(cv[0].color.float32, clear, 4 * sizeof(float));
    cv[1].depthStencil.depth = 1.0f;
    VkRenderPassBeginInfo bi = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
    bi.renderPass = rp;
    bi.framebuffer = fb;
    bi.renderArea.extent.width = g.width;
    bi.renderArea.extent.height = g.height;
    bi.clearValueCount = fb == g.fb_cd ? 2 : 1;
    bi.pClearValues = cv;
    vkCmdBeginRenderPass(cb, &bi, VK_SUBPASS_CONTENTS_INLINE);
}

static void push(VkCommandBuffer cb, VkPipelineLayout l, const void *data, uint32_t size)
{
    vkCmdPushConstants(cb, l, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, size, data);
}

static int near_color(const uint8_t a[4], const uint8_t b[4], int tol)
{
    for (int i = 0; i < 4; i++)
        if (abs((int)a[i] - (int)b[i]) > tol) return 0;
    return 1;
}

/* ---- textures ---- */

#define TEX_BIG 2048
#define TEX_CUBE 256

static void gen_noise(uint8_t *dst, VkDeviceSize off, VkDeviceSize n, const void *ctx)
{
    for (VkDeviceSize i = 0; i < n; i += 4) {
        uint32_t texel = (uint32_t)((off + i) / 4);
        uint32_t h = hash32(texel + 0x1234567u);
        memcpy(dst + i, &h, 4);
    }
}

static uint8_t noise_at(uint32_t x, uint32_t y, int c)
{
    uint32_t h = hash32(y * TEX_BIG + x + 0x1234567u);
    return (uint8_t)(h >> (8 * c));
}

static void gen_checker(uint8_t *dst, VkDeviceSize off, VkDeviceSize n, const void *ctx)
{
    for (VkDeviceSize i = 0; i < n; i += 4) {
        uint32_t texel = (uint32_t)((off + i) / 4), x = texel % TEX_CUBE, y = texel / TEX_CUBE;
        static const uint8_t a[4] = { 235, 235, 235, 255 }, b[4] = { 40, 90, 200, 255 };
        memcpy(dst + i, ((x / 32 + y / 32) & 1) ? a : b, 4);
    }
}

struct texture {
    VkImage img;
    VkDeviceMemory mem;
    VkImageView view;
};

static VkResult make_texture(uint32_t size, gen_fn gen, struct texture *t)
{
    VkResult r = make_image(size, size, VK_FORMAT_R8G8B8A8_UNORM,
                            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &t->img, &t->mem);
    if (!r) r = upload_image(t->img, size, size, gen, NULL);
    if (!r) r = make_view(t->img, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, &t->view);
    return r;
}

static void free_texture(struct texture *t)
{
    vkDestroyImageView(g.device, t->view, NULL);
    vkDestroyImage(g.device, t->img, NULL);
    vkFreeMemory(g.device, t->mem, NULL);
}

static void scene_done(void)
{
    vkDeviceWaitIdle(g.device);
    vkResetDescriptorPool(g.device, g.dpool, 0);
}

#define CK(expr, what) do { VkResult r_ = (expr); if (r_ != VK_SUCCESS) { rc = fail(name, r_, what); goto out; } } while (0)

/* ---- scenes ---- */

/* cube: one textured, lit, spinning cube; mostly per-frame overhead. */
struct cube_ctx {
    VkPipeline pipe;
    VkDescriptorSet set;
};

static void cube_record(VkCommandBuffer cb, double t, void *p)
{
    struct cube_ctx *c = p;
    static const float clear[4] = { 0.1f, 0.1f, 0.12f, 1.0f };
    float proj[16], view[16] = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -4, 1 }, model[16], pv[16], m[32];
    perspective(proj, 1.0472f, (float)g.width / (float)g.height, 0.1f, 100.0f);
    rotation(model, (float)t, (float)t * 0.7f);
    mat_mul(pv, proj, view);
    mat_mul(m, pv, model);
    memcpy(m + 16, model, sizeof(model));
    begin_pass(cb, g.rp_cd, g.fb_cd, clear);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c->pipe);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pl_tex, 0, 1, &c->set, 0, NULL);
    push(cb, g.pl_tex, m, 128);
    vkCmdDraw(cb, 36, 1, 0, 0);
    vkCmdEndRenderPass(cb);
}

static int scene_cube(const char *name)
{
    int rc = 0;
    struct texture tex = { 0 };
    struct cube_ctx c = { 0 };
    struct stats st;
    CK(make_texture(TEX_CUBE, gen_checker, &tex), "uploading the texture");
    CK(alloc_set(g.dsl_tex, &c.set), "allocating a descriptor set");
    set_texture(c.set, tex.view);
    struct gpipe d = { 0 };
    d.vs = spv_cube_vert; d.vs_size = sizeof(spv_cube_vert);
    d.fs = spv_cube_frag; d.fs_size = sizeof(spv_cube_frag);
    d.layout = g.pl_tex;
    d.rp = g.rp_cd;
    d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    d.depth = 1;
    CK(make_gpipe(&d, &c.pipe), "creating the pipeline");
    struct loop L = { name, cube_record, &c, 1, 1 };
    if ((rc = run_loop(&L, &st))) goto out;
    uint8_t px[4];
    static const uint8_t bg[4] = { 26, 26, 31, 255 };
    CK(read_pixel(g.width / 2, g.height / 2, px), "reading the picture");
    print_frame_result(name, &st, near_color(px, bg, 12) ? "differs" : "ok");
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    free_texture(&tex);
    return rc;
}

/* fill: 16 full-screen quads added on top of each other. */
#define FILL_LAYERS 16
struct simple_ctx {
    VkPipeline pipe;
    VkDescriptorSet set;
};

static void fill_record(VkCommandBuffer cb, double t, void *p)
{
    struct simple_ctx *c = p;
    static const float black[4] = { 0, 0, 0, 0 };
    struct pc48 pc = { { 4 / 255.0f, 8 / 255.0f, 12 / 255.0f, 2 / 255.0f }, { 1, 1, 0, 0 }, { 0 } };
    begin_pass(cb, g.rp_c_clear, g.fb_c, black);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c->pipe);
    for (int i = 0; i < FILL_LAYERS; i++) {
        push(cb, g.pl_plain, &pc, sizeof(pc));
        vkCmdDraw(cb, 3, 1, 0, 0);
    }
    vkCmdEndRenderPass(cb);
}

static int scene_fill(const char *name)
{
    int rc = 0;
    struct simple_ctx c = { 0 };
    struct stats st;
    struct gpipe d = { 0 };
    d.vs = spv_fullscreen_vert; d.vs_size = sizeof(spv_fullscreen_vert);
    d.fs = spv_color_frag; d.fs_size = sizeof(spv_color_frag);
    d.layout = g.pl_plain;
    d.rp = g.rp_c_clear;
    d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    d.blend_add = 1;
    CK(make_gpipe(&d, &c.pipe), "creating the pipeline");
    struct loop L = { name, fill_record, &c, 0, 1 };
    if ((rc = run_loop(&L, &st))) goto out;
    uint8_t px[4];
    static const uint8_t want[4] = { 64, 128, 192, 32 };
    CK(read_pixel(g.width / 2, g.height / 2, px), "reading the picture");
    print_frame_result(name, &st, near_color(px, want, 0) ? "ok" : "differs");
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    return rc;
}

/* texture: 8 full-screen quads sampling a 2048x2048 noise texture, each weighted 1/8. */
#define TEX_LAYERS 8

static void tex_layer(int i, float *scale, float *offset)
{
    *scale = 1.0f + 0.25f * (float)i;
    *offset = 0.37f * (float)i;
}

static void texture_record(VkCommandBuffer cb, double t, void *p)
{
    struct simple_ctx *c = p;
    static const float black[4] = { 0, 0, 0, 0 };
    begin_pass(cb, g.rp_c_clear, g.fb_c, black);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c->pipe);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pl_tex, 0, 1, &c->set, 0, NULL);
    for (int i = 0; i < TEX_LAYERS; i++) {
        float s, o;
        tex_layer(i, &s, &o);
        struct pc48 pc = { { 0.125f, 0.125f, 0.125f, 0.125f }, { s, s, o, o * 0.5f }, { 0 } };
        push(cb, g.pl_tex, &pc, sizeof(pc));
        vkCmdDraw(cb, 3, 1, 0, 0);
    }
    vkCmdEndRenderPass(cb);
}

/* What the center pixel should be: bilinear, repeat, then 8-bit adds. */
static void texture_expected(uint8_t out[4])
{
    double u0 = (g.width / 2 + 0.5) / g.width, v0 = (g.height / 2 + 0.5) / g.height;
    double acc[4] = { 0, 0, 0, 0 };
    for (int i = 0; i < TEX_LAYERS; i++) {
        float s, o;
        tex_layer(i, &s, &o);
        double tx = (u0 * s + o) * TEX_BIG - 0.5, ty = (v0 * s + o * 0.5) * TEX_BIG - 0.5;
        double fx = floor(tx), fy = floor(ty), ax = tx - fx, ay = ty - fy;
        uint32_t x0 = (uint32_t)(((long)fx % TEX_BIG + TEX_BIG) % TEX_BIG), x1 = (x0 + 1) % TEX_BIG;
        uint32_t y0 = (uint32_t)(((long)fy % TEX_BIG + TEX_BIG) % TEX_BIG), y1 = (y0 + 1) % TEX_BIG;
        for (int ch = 0; ch < 4; ch++) {
            double v = (1 - ax) * (1 - ay) * noise_at(x0, y0, ch) + ax * (1 - ay) * noise_at(x1, y0, ch) +
                       (1 - ax) * ay * noise_at(x0, y1, ch) + ax * ay * noise_at(x1, y1, ch);
            acc[ch] = floor(acc[ch] + v * 0.125 + 0.5);
            if (acc[ch] > 255) acc[ch] = 255;
        }
    }
    for (int ch = 0; ch < 4; ch++) out[ch] = (uint8_t)acc[ch];
}

static int scene_texture(const char *name)
{
    int rc = 0;
    struct texture tex = { 0 };
    struct simple_ctx c = { 0 };
    struct stats st;
    CK(make_texture(TEX_BIG, gen_noise, &tex), "uploading the texture");
    CK(alloc_set(g.dsl_tex, &c.set), "allocating a descriptor set");
    set_texture(c.set, tex.view);
    struct gpipe d = { 0 };
    d.vs = spv_fullscreen_vert; d.vs_size = sizeof(spv_fullscreen_vert);
    d.fs = spv_texture_frag; d.fs_size = sizeof(spv_texture_frag);
    d.layout = g.pl_tex;
    d.rp = g.rp_c_clear;
    d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    d.blend_add = 1;
    CK(make_gpipe(&d, &c.pipe), "creating the pipeline");
    struct loop L = { name, texture_record, &c, 0, 1 };
    if ((rc = run_loop(&L, &st))) goto out;
    uint8_t px[4], want[4];
    CK(read_pixel(g.width / 2, g.height / 2, px), "reading the picture");
    texture_expected(want);
    print_frame_result(name, &st, near_color(px, want, 8) ? "ok" : "differs");
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    free_texture(&tex);
    return rc;
}

/* geometry: a ~1M-triangle wave grid with depth test. */
#define GRID 708
#define GRID_V ((GRID + 1) * (GRID + 1))
#define GRID_I (GRID * GRID * 6)

static void gen_grid_vertices(uint8_t *dst, VkDeviceSize off, VkDeviceSize n, const void *ctx)
{
    for (VkDeviceSize i = 0; i < n; i += 8) {
        uint32_t k = (uint32_t)((off + i) / 8), x = k % (GRID + 1), z = k / (GRID + 1);
        float v[2] = { -1.5f + 3.0f * (float)x / GRID, -1.5f + 3.0f * (float)z / GRID };
        memcpy(dst + i, v, 8);
    }
}

static void gen_grid_indices(uint8_t *dst, VkDeviceSize off, VkDeviceSize n, const void *ctx)
{
    for (VkDeviceSize i = 0; i < n; i += 4) {
        uint32_t k = (uint32_t)((off + i) / 4), q = k / 6, corner = k % 6;
        uint32_t qx = q % GRID, qz = q / GRID, v00 = qz * (GRID + 1) + qx;
        uint32_t c[6] = { v00, v00 + 1, v00 + GRID + 1, v00 + GRID + 1, v00 + 1, v00 + GRID + 2 };
        memcpy(dst + i, &c[corner], 4);
    }
}

struct geo_ctx {
    VkPipeline pipe;
    VkBuffer vb, ib;
};

static void geometry_record(VkCommandBuffer cb, double t, void *p)
{
    struct geo_ctx *c = p;
    static const float black[4] = { 0, 0, 0, 1 };
    static const float eye[3] = { 0, 1.1f, 1.5f }, at[3] = { 0, 0, 0 };
    float proj[16], view[16], m[20];
    perspective(proj, 1.0472f, (float)g.width / (float)g.height, 0.05f, 50.0f);
    look_at(view, eye, at);
    mat_mul(m, proj, view);
    m[16] = (float)t;
    m[17] = m[18] = m[19] = 0;
    VkDeviceSize zero = 0;
    begin_pass(cb, g.rp_cd, g.fb_cd, black);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c->pipe);
    vkCmdBindVertexBuffers(cb, 0, 1, &c->vb, &zero);
    vkCmdBindIndexBuffer(cb, c->ib, 0, VK_INDEX_TYPE_UINT32);
    push(cb, g.pl_plain, m, sizeof(m));
    vkCmdDrawIndexed(cb, GRID_I, 1, 0, 0, 0);
    vkCmdEndRenderPass(cb);
}

static int scene_geometry(const char *name)
{
    int rc = 0;
    struct geo_ctx c = { 0 };
    VkDeviceMemory vm = VK_NULL_HANDLE, im = VK_NULL_HANDLE;
    struct stats st;
    CK(make_buffer((VkDeviceSize)GRID_V * 8, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                   VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &c.vb, &vm, NULL), "creating the vertex buffer");
    CK(make_buffer((VkDeviceSize)GRID_I * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                   VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &c.ib, &im, NULL), "creating the index buffer");
    CK(upload_buffer(c.vb, (VkDeviceSize)GRID_V * 8, gen_grid_vertices, NULL), "uploading vertices");
    CK(upload_buffer(c.ib, (VkDeviceSize)GRID_I * 4, gen_grid_indices, NULL), "uploading indices");
    struct gpipe d = { 0 };
    d.vs = spv_geometry_vert; d.vs_size = sizeof(spv_geometry_vert);
    d.fs = spv_geometry_frag; d.fs_size = sizeof(spv_geometry_frag);
    d.layout = g.pl_plain;
    d.rp = g.rp_cd;
    d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    d.depth = 1;
    d.vertex_vec2 = 1;
    CK(make_gpipe(&d, &c.pipe), "creating the pipeline");
    struct loop L = { name, geometry_record, &c, 1, 1 };
    if ((rc = run_loop(&L, &st))) goto out;
    uint8_t px[4];
    static const uint8_t bg[4] = { 0, 0, 0, 255 };
    CK(read_pixel(g.width / 2, g.height / 2, px), "reading the picture");
    print_frame_result(name, &st, near_color(px, bg, 10) ? "differs" : "ok");
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    vkDestroyBuffer(g.device, c.vb, NULL);
    vkDestroyBuffer(g.device, c.ib, NULL);
    vkFreeMemory(g.device, vm, NULL);
    vkFreeMemory(g.device, im, NULL);
    return rc;
}

/* draws: 20000 tiny rectangles, one draw call each, recorded every frame. */
#define DRAWS 20000

static void draws_record(VkCommandBuffer cb, double t, void *p)
{
    struct simple_ctx *c = p;
    static const float black[4] = { 0, 0, 0, 1 };
    begin_pass(cb, g.rp_c_clear, g.fb_c, black);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c->pipe);
    float phase = (float)t;
    for (int i = 0; i < DRAWS; i++) {
        float x = -1.0f + (float)(i % 200) * 0.01f, y = -1.0f + (float)(i / 200) * 0.02f;
        struct pc48 pc = { { 0.5f + 0.5f * sinf(phase + (float)i * 0.01f), (float)(i % 200) / 200.0f,
                             (float)(i / 200) / 100.0f, 1.0f },
                           { 0 }, { x, y, x + 0.006f, y + 0.012f } };
        push(cb, g.pl_plain, &pc, sizeof(pc));
        vkCmdDraw(cb, 4, 1, 0, 0);
    }
    vkCmdEndRenderPass(cb);
}

static int scene_draws(const char *name)
{
    int rc = 0;
    struct simple_ctx c = { 0 };
    struct stats st;
    struct gpipe d = { 0 };
    d.vs = spv_rect_vert; d.vs_size = sizeof(spv_rect_vert);
    d.fs = spv_color_frag; d.fs_size = sizeof(spv_color_frag);
    d.layout = g.pl_plain;
    d.rp = g.rp_c_clear;
    d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    CK(make_gpipe(&d, &c.pipe), "creating the pipeline");
    struct loop L = { name, draws_record, &c, 1, 1 };
    if ((rc = run_loop(&L, &st))) goto out;
    print_frame_result(name, &st, "none");
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    return rc;
}

/* passes: a clear, then 8 render passes that each load and store the whole image.
 * Each pass draws 4x4-pixel squares spread over the image (48 x 27 grid), so
 * no screen tile is left out; the shading work stays tiny. */
#define PASSES 8
#define DOT_COLS 48
#define DOT_ROWS 27

static void passes_record(VkCommandBuffer cb, double t, void *p)
{
    struct simple_ctx *c = p;
    static const float black[4] = { 0, 0, 0, 0 };
    float pc[8] = { 8 / 255.0f, 8 / 255.0f, 8 / 255.0f, 8 / 255.0f, DOT_COLS, DOT_ROWS,
                    8.0f / (float)g.width, 8.0f / (float)g.height };
    begin_pass(cb, g.rp_c_clear, g.fb_c, black);
    vkCmdEndRenderPass(cb);
    for (int i = 0; i < PASSES; i++) {
        begin_pass(cb, g.rp_c_load, g.fb_c, NULL);
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c->pipe);
        push(cb, g.pl_plain, pc, sizeof(pc));
        vkCmdDraw(cb, 4, DOT_COLS * DOT_ROWS, 0, 0);
        vkCmdEndRenderPass(cb);
    }
}

static int scene_passes(const char *name)
{
    int rc = 0;
    struct simple_ctx c = { 0 };
    struct stats st;
    struct gpipe d = { 0 };
    d.vs = spv_dots_vert; d.vs_size = sizeof(spv_dots_vert);
    d.fs = spv_color_frag; d.fs_size = sizeof(spv_color_frag);
    d.layout = g.pl_plain;
    d.rp = g.rp_c_load;
    d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    d.blend_add = 1;
    CK(make_gpipe(&d, &c.pipe), "creating the pipeline");
    struct loop L = { name, passes_record, &c, 0, 1 };
    if ((rc = run_loop(&L, &st))) goto out;
    uint8_t px[4];
    static const uint8_t want[4] = { 64, 64, 64, 64 };
    /* A pixel inside the square of cell (24, 13). */
    uint32_t x = (uint32_t)floor(24.5 * g.width / DOT_COLS) - 1, y = (uint32_t)floor(13.5 * g.height / DOT_ROWS) - 1;
    CK(read_pixel(x, y, px), "reading the picture");
    print_frame_result(name, &st, near_color(px, want, 0) ? "ok" : "differs");
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    return rc;
}

/* compute: 1M invocations, 2048 multiply-adds each. */
#define ALU_ITEMS (1u << 20)
static const float alu_k[4] = { 0.999f, 0.001f, 1.0005f, -0.0005f };

struct comp_ctx {
    VkPipeline pipe;
    VkDescriptorSet set;
    uint32_t groups;
};

static void comp_record(VkCommandBuffer cb, double t, void *p)
{
    struct comp_ctx *c = p;
    memory_barrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, c->pipe);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g.pl_comp, 0, 1, &c->set, 0, NULL);
    vkCmdPushConstants(cb, g.pl_comp, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(alu_k), alu_k);
    vkCmdDispatch(cb, c->groups, 1, 1);
}

static void alu_cpu(uint32_t i, float x[4])
{
    x[0] = (float)(i & 1023u) * 0.001f;
    x[1] = (float)((i >> 10) & 1023u) * 0.001f;
    x[2] = 0.5f;
    x[3] = 0.25f;
    for (int r = 0; r < 256; r++)
        for (int c = 0; c < 4; c++) {
            x[c] = x[c] * alu_k[0] + alu_k[1];
            x[c] = x[c] * alu_k[2] + alu_k[3];
        }
}

static int scene_compute(const char *name)
{
    int rc = 0;
    struct comp_ctx c = { 0 };
    VkBuffer out_b = VK_NULL_HANDLE;
    VkDeviceMemory out_m = VK_NULL_HANDLE;
    struct stats st;
    CK(make_buffer((VkDeviceSize)ALU_ITEMS * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                   VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &out_b, &out_m, NULL), "creating the buffer");
    CK(alloc_set(g.dsl_comp, &c.set), "allocating a descriptor set");
    set_buffer(c.set, 0, out_b);
    set_buffer(c.set, 1, out_b);
    CK(make_cpipe(SHADER(spv_alu_comp), &c.pipe), "creating the pipeline");
    c.groups = ALU_ITEMS / 64;
    struct loop L = { name, comp_record, &c, 0, 0 };
    if ((rc = run_loop(&L, &st))) goto out;
    static const uint32_t idx[4] = { 0, 4097, 777777, ALU_ITEMS - 1 };
    VkDeviceSize offs[4];
    for (int i = 0; i < 4; i++) offs[i] = (VkDeviceSize)idx[i] * 16;
    CK(read_buffer(out_b, offs, 4, 16, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT), "reading the results");
    const char *check = "ok";
    for (int i = 0; i < 4; i++) {
        float want[4], got[4];
        alu_cpu(idx[i], want);
        memcpy(got, g.readback_map + i * 16, 16);
        for (int k = 0; k < 4; k++)
            if (!(fabsf(got[k] - want[k]) <= 1e-4f + 1e-3f * fabsf(want[k]))) check = "differs";
    }
    print_frame_result(name, &st, check);
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    vkDestroyBuffer(g.device, out_b, NULL);
    vkFreeMemory(g.device, out_m, NULL);
    return rc;
}

/* bandwidth: a shader copies 64 MiB from one buffer to another. */
#define BW_BYTES (64u << 20)

static uint32_t bw_word(uint32_t j)
{
    return j * 2654435761u ^ 0x9e3779b9u;
}

static void gen_bw(uint8_t *dst, VkDeviceSize off, VkDeviceSize n, const void *ctx)
{
    for (VkDeviceSize i = 0; i < n; i += 4) {
        uint32_t w = bw_word((uint32_t)((off + i) / 4));
        memcpy(dst + i, &w, 4);
    }
}

static int scene_bandwidth(const char *name)
{
    int rc = 0;
    struct comp_ctx c = { 0 };
    VkBuffer in_b = VK_NULL_HANDLE, out_b = VK_NULL_HANDLE;
    VkDeviceMemory in_m = VK_NULL_HANDLE, out_m = VK_NULL_HANDLE;
    struct stats st;
    CK(make_buffer(BW_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                   VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &in_b, &in_m, NULL), "creating the input buffer");
    CK(make_buffer(BW_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                   VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &out_b, &out_m, NULL), "creating the output buffer");
    CK(upload_buffer(in_b, BW_BYTES, gen_bw, NULL), "uploading the input");
    CK(alloc_set(g.dsl_comp, &c.set), "allocating a descriptor set");
    set_buffer(c.set, 0, in_b);
    set_buffer(c.set, 1, out_b);
    CK(make_cpipe(SHADER(spv_copy_comp), &c.pipe), "creating the pipeline");
    c.groups = BW_BYTES / 16 / 128;
    struct loop L = { name, comp_record, &c, 0, 0 };
    if ((rc = run_loop(&L, &st))) goto out;
    static const uint32_t idx[4] = { 0, 12345, 9999999, BW_BYTES / 4 - 1 };
    VkDeviceSize offs[4];
    for (int i = 0; i < 4; i++) offs[i] = (VkDeviceSize)idx[i] * 4;
    CK(read_buffer(out_b, offs, 4, 4, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT), "reading the results");
    const char *check = "ok";
    for (int i = 0; i < 4; i++) {
        uint32_t w;
        memcpy(&w, g.readback_map + i * 4, 4);
        if (w != bw_word(idx[i])) check = "differs";
    }
    double gbs = 2.0 * BW_BYTES / 1e9 / (st.avg / 1000.0);
    print_result(name, &st, gbs, "GB/s", "higher", check);
out:
    scene_done();
    vkDestroyPipeline(g.device, c.pipe, NULL);
    vkDestroyBuffer(g.device, in_b, NULL);
    vkDestroyBuffer(g.device, out_b, NULL);
    vkFreeMemory(g.device, in_m, NULL);
    vkFreeMemory(g.device, out_m, NULL);
    return rc;
}

/* pipelines: 40 pipelines whose fragment shader gets other constants each time. */
#define PIPELINES 40

static VkResult one_pipeline(int rounds, float scale, VkPipeline *p)
{
    struct { int32_t rounds; float scale; } data = { rounds, scale };
    VkSpecializationMapEntry e[2] = { { 0, 0, 4 }, { 1, 4, 4 } };
    VkSpecializationInfo si = { 2, e, sizeof(data), &data };
    struct gpipe d = { 0 };
    d.vs = spv_fullscreen_vert; d.vs_size = sizeof(spv_fullscreen_vert);
    d.fs = spv_pipeline_frag; d.fs_size = sizeof(spv_pipeline_frag);
    d.layout = g.pl_plain;
    d.rp = g.rp_c_clear;
    d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    d.fs_spec = &si;
    return make_gpipe(&d, p);
}

static int scene_pipelines(const char *name)
{
    int rc = 0;
    VkPipeline p = VK_NULL_HANDLE;
    struct stats st = { 0 };
    uint32_t seed = hash32((uint32_t)time(NULL) ^ ((uint32_t)getpid() << 16));
    for (int i = 0; i < 2; i++) {
        CK(one_pipeline(8 + i, 0.5f + (float)(hash32(seed + 99u + (uint32_t)i) % 1000) / 4000.0f, &p),
           "creating a warm-up pipeline");
        vkDestroyPipeline(g.device, p, NULL);
        p = VK_NULL_HANDLE;
    }
    double start = now();
    for (int i = 0; i < PIPELINES; i++) {
        float scale = 1.0f + (float)(hash32(seed + (uint32_t)i) % 100000) / 200000.0f;
        double t0 = now();
        CK(one_pipeline(16 + i % 16, scale, &p), "creating a pipeline");
        g.times[i] = (now() - t0) * 1000.0;
        vkDestroyPipeline(g.device, p, NULL);
        p = VK_NULL_HANDLE;
    }
    st.frames = PIPELINES;
    st.seconds = now() - start;
    double sum = 0;
    for (int i = 0; i < PIPELINES; i++) sum += g.times[i];
    st.avg = sum / PIPELINES;
    qsort(g.times, PIPELINES, sizeof(double), cmp_double);
    st.min = g.times[0];
    st.max = g.times[PIPELINES - 1];
    st.p50 = g.times[(PIPELINES - 1) / 2];
    st.p99 = g.times[(int)((PIPELINES - 1) * 0.99)];
    print_result(name, &st, st.avg, "ms", "lower", "none");
out:
    scene_done();
    vkDestroyPipeline(g.device, p, NULL);
    return rc;
}

/* ---- setup ---- */

static const struct {
    const char *id;
    int (*run)(const char *);
} scenes[] = {
    { "cube", scene_cube },         { "fill", scene_fill },         { "texture", scene_texture },
    { "geometry", scene_geometry }, { "draws", scene_draws },       { "passes", scene_passes },
    { "compute", scene_compute },   { "bandwidth", scene_bandwidth }, { "pipelines", scene_pipelines },
};
#define SCENE_COUNT (int)(sizeof(scenes) / sizeof(scenes[0]))

static int init_fail(const char *what, const char *detail)
{
    printf("DLBENCH error scene=- msg=%s%s%s\n", what, detail ? ": " : "", detail ? detail : "");
    return 2;
}

static VkResult make_render_pass(int depth, VkAttachmentLoadOp color_load, VkRenderPass *rp)
{
    VkAttachmentDescription a[2] = { { 0 }, { 0 } };
    a[0].format = VK_FORMAT_R8G8B8A8_UNORM;
    a[0].samples = VK_SAMPLE_COUNT_1_BIT;
    a[0].loadOp = color_load;
    a[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    a[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    a[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a[0].initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    a[0].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    a[1].format = g.depth_format;
    a[1].samples = VK_SAMPLE_COUNT_1_BIT;
    a[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    a[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    a[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    a[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    VkAttachmentReference cr = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
    VkAttachmentReference dr = { 1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL };
    VkSubpassDescription sp = { 0 };
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sp.colorAttachmentCount = 1;
    sp.pColorAttachments = &cr;
    sp.pDepthStencilAttachment = depth ? &dr : NULL;
    // Earlier frames and copies before, copies after.
    VkSubpassDependency dep[2] = { { 0 }, { 0 } };
    dep[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    dep[0].dstSubpass = 0;
    dep[0].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT |
                          VK_PIPELINE_STAGE_TRANSFER_BIT;
    dep[0].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dep[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                          VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    dep[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                           VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dep[1].srcSubpass = 0;
    dep[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    dep[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dep[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    dep[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    VkRenderPassCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO };
    ci.attachmentCount = depth ? 2 : 1;
    ci.pAttachments = a;
    ci.subpassCount = 1;
    ci.pSubpasses = &sp;
    ci.dependencyCount = 2;
    ci.pDependencies = dep;
    return vkCreateRenderPass(g.device, &ci, NULL, rp);
}

static VkResult make_framebuffer(VkRenderPass rp, int depth, VkFramebuffer *fb)
{
    VkImageView v[2] = { g.color_view, g.depth_view };
    VkFramebufferCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO };
    ci.renderPass = rp;
    ci.attachmentCount = depth ? 2 : 1;
    ci.pAttachments = v;
    ci.width = g.width;
    ci.height = g.height;
    ci.layers = 1;
    return vkCreateFramebuffer(g.device, &ci, NULL, fb);
}

static VkResult make_layout(VkDescriptorSetLayout set, VkShaderStageFlags stages, uint32_t push_size, VkPipelineLayout *l)
{
    VkPushConstantRange pr = { stages, 0, push_size };
    VkPipelineLayoutCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
    ci.setLayoutCount = set ? 1 : 0;
    ci.pSetLayouts = &set;
    ci.pushConstantRangeCount = 1;
    ci.pPushConstantRanges = &pr;
    return vkCreatePipelineLayout(g.device, &ci, NULL, l);
}

/* Shared objects: images, render passes, layouts, command buffers. */
static VkResult init_shared(void)
{
    VkResult r;
    static const VkFormat depths[] = { VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D16_UNORM };
    g.depth_format = VK_FORMAT_UNDEFINED;
    for (unsigned i = 0; i < sizeof(depths) / sizeof(depths[0]); i++) {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(g.phys, depths[i], &fp);
        if (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) {
            g.depth_format = depths[i];
            break;
        }
    }
    if (g.depth_format == VK_FORMAT_UNDEFINED) return VK_ERROR_FORMAT_NOT_SUPPORTED;
    g.depth_aspect = VK_IMAGE_ASPECT_DEPTH_BIT |
                     (g.depth_format == VK_FORMAT_D24_UNORM_S8_UINT ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);

    if ((r = make_image(g.width, g.height, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, &g.color, &g.color_mem)))
        return r;
    if ((r = make_image(g.width, g.height, g.depth_format, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, &g.depth,
                        &g.depth_mem)))
        return r;
    if ((r = make_view(g.color, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, &g.color_view))) return r;
    if ((r = make_view(g.depth, g.depth_format, g.depth_aspect, &g.depth_view))) return r;
    if ((r = make_render_pass(1, VK_ATTACHMENT_LOAD_OP_CLEAR, &g.rp_cd))) return r;
    if ((r = make_render_pass(0, VK_ATTACHMENT_LOAD_OP_CLEAR, &g.rp_c_clear))) return r;
    if ((r = make_render_pass(0, VK_ATTACHMENT_LOAD_OP_LOAD, &g.rp_c_load))) return r;
    if ((r = make_framebuffer(g.rp_cd, 1, &g.fb_cd))) return r;
    if ((r = make_framebuffer(g.rp_c_clear, 0, &g.fb_c))) return r;

    VkCommandPoolCreateInfo pci = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = g.qfam;
    if ((r = vkCreateCommandPool(g.device, &pci, NULL, &g.pool))) return r;
    VkCommandBuffer cbs[2];
    VkCommandBufferAllocateInfo cai = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, g.pool,
                                        VK_COMMAND_BUFFER_LEVEL_PRIMARY, 2 };
    if ((r = vkAllocateCommandBuffers(g.device, &cai, cbs))) return r;
    g.util_cb = cbs[0];
    g.frame_cb = cbs[1];
    VkFenceCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    if ((r = vkCreateFence(g.device, &fci, NULL, &g.fence))) return r;
    if (g.ts_bits) {
        VkQueryPoolCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO };
        qci.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qci.queryCount = 2;
        if ((r = vkCreateQueryPool(g.device, &qci, NULL, &g.queries))) return r;
    }

    VkSamplerCreateInfo sci = { .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    sci.maxLod = 0.0f;
    if ((r = vkCreateSampler(g.device, &sci, NULL, &g.sampler))) return r;

    VkDescriptorSetLayoutBinding tb = { 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL };
    VkDescriptorSetLayoutCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
    dci.bindingCount = 1;
    dci.pBindings = &tb;
    if ((r = vkCreateDescriptorSetLayout(g.device, &dci, NULL, &g.dsl_tex))) return r;
    VkDescriptorSetLayoutBinding sb[2] = { { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL },
                                           { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL } };
    dci.bindingCount = 2;
    dci.pBindings = sb;
    if ((r = vkCreateDescriptorSetLayout(g.device, &dci, NULL, &g.dsl_comp))) return r;
    VkShaderStageFlags vf = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    if ((r = make_layout(VK_NULL_HANDLE, vf, 128, &g.pl_plain))) return r;
    if ((r = make_layout(g.dsl_tex, vf, 128, &g.pl_tex))) return r;
    if ((r = make_layout(g.dsl_comp, VK_SHADER_STAGE_COMPUTE_BIT, 16, &g.pl_comp))) return r;
    VkDescriptorPoolSize ps[2] = { { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 4 }, { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 8 } };
    VkDescriptorPoolCreateInfo dpi = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
    dpi.maxSets = 4;
    dpi.poolSizeCount = 2;
    dpi.pPoolSizes = ps;
    if ((r = vkCreateDescriptorPool(g.device, &dpi, NULL, &g.dpool))) return r;

    VkMemoryPropertyFlags host = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    if ((r = make_buffer(STAGING_SIZE, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, host, &g.staging, &g.staging_mem, &g.staging_map)))
        return r;
    VkDeviceSize rb = (VkDeviceSize)g.preview_w * g.preview_h * 4;
    if (rb < 4096) rb = 4096;
    if ((r = make_buffer(rb, VK_BUFFER_USAGE_TRANSFER_DST_BIT, host | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, &g.readback,
                         &g.readback_mem, &g.readback_map))) {
        vkDestroyBuffer(g.device, g.readback, NULL);
        g.readback = VK_NULL_HANDLE;
        if ((r = make_buffer(rb, VK_BUFFER_USAGE_TRANSFER_DST_BIT, host, &g.readback, &g.readback_mem, &g.readback_map)))
            return r;
    }
    if (g.preview_path &&
        (r = make_image(g.preview_w, g.preview_h, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, &g.preview, &g.preview_mem)))
        return r;

    // The color image stays in COLOR_ATTACHMENT_OPTIMAL between uses.
    if ((r = begin(g.util_cb, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))) return r;
    image_barrier(g.util_cb, g.color, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                  VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    return end_submit(g.util_cb);
}

static int init_vulkan(const char *lib)
{
    // Pipeline timing must not come from Mesa's disk cache.
    setenv("MESA_SHADER_CACHE_DISABLE", "true", 0);
    void *h = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!h) return init_fail("could not open the Vulkan library", dlerror());
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(h, "vkGetInstanceProcAddr");
    if (!gipa) return init_fail("the Vulkan library has no vkGetInstanceProcAddr", lib);
    vkCreateInstance = (PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!vkCreateInstance) return init_fail("no vkCreateInstance", NULL);

    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO };
    app.pApplicationName = "DriverLab speed test";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    ici.pApplicationInfo = &app;
    VkResult r = vkCreateInstance(&ici, NULL, &g.instance);
    if (r) return init_fail("vkCreateInstance failed", vkres(r));
#define LOAD_I(f) if (!(f = (PFN_##f)gipa(g.instance, #f))) return init_fail("driver is missing " #f, NULL);
    FUNCS_INSTANCE(LOAD_I)

    uint32_t n = 1;
    r = vkEnumeratePhysicalDevices(g.instance, &n, &g.phys);
    if ((r != VK_SUCCESS && r != VK_INCOMPLETE) || n == 0) return init_fail("no Vulkan GPU found", vkres(r));
    vkGetPhysicalDeviceProperties(g.phys, &g.props);
    vkGetPhysicalDeviceMemoryProperties(g.phys, &g.mem);
    VkQueueFamilyProperties qf[16];
    uint32_t qn = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(g.phys, &qn, qf);
    g.qfam = UINT32_MAX;
    for (uint32_t i = 0; i < qn; i++) {
        VkQueueFlags want = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
        if ((qf[i].queueFlags & want) == want) {
            g.qfam = i;
            g.ts_bits = qf[i].timestampValidBits;
            break;
        }
    }
    if (g.qfam == UINT32_MAX) return init_fail("no graphics + compute queue", NULL);

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    qci.queueFamilyIndex = g.qfam;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    if ((r = vkCreateDevice(g.phys, &dci, NULL, &g.device))) return init_fail("vkCreateDevice failed", vkres(r));
#define LOAD_D(f) if (!(f = (PFN_##f)vkGetDeviceProcAddr(g.device, #f))) return init_fail("driver is missing " #f, NULL);
    FUNCS_DEVICE(LOAD_D)
    vkGetDeviceQueue(g.device, g.qfam, 0, &g.queue);

    uint32_t api = g.props.apiVersion, dv = g.props.driverVersion;
    char apis[32], dvs[32];
    snprintf(apis, sizeof(apis), "%u.%u.%u", VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api), VK_VERSION_PATCH(api));
    snprintf(dvs, sizeof(dvs), "%u.%u.%u", VK_VERSION_MAJOR(dv), VK_VERSION_MINOR(dv), VK_VERSION_PATCH(dv));
    printf("DLBENCH device name=%s api=%s driver=%s driver_raw=0x%08x vendor=0x%04x device=0x%08x timestamps=%d\n",
           enc(g.props.deviceName), apis, dvs, dv, g.props.vendorID, g.props.deviceID, g.ts_bits ? 1 : 0);

    if ((r = init_shared())) return init_fail("setting up the test images failed", vkres(r));
    return 0;
}

static void usage(void)
{
    fprintf(stderr, "usage: libdlbench.so [--vk-lib PATH] [--scenes id,id,...] [--seconds S] [--warmup S]\n"
                    "                     [--width W --height H] [--preview PATH] [--preview-size WxH] [--list]\n");
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    const char *lib = "libvulkan.so", *list = NULL;
    g.seconds = 5;
    g.warmup = 1;
    g.width = 1920;
    g.height = 1080;
    g.preview_w = 320;
    g.preview_h = 180;
    for (int i = 1; i < argc; i++)
        if (!strcmp(argv[i], "--list")) {
            for (int s = 0; s < SCENE_COUNT; s++) printf("DLBENCH scene id=%s\n", scenes[s].id);
            return 0;
        }
    for (int i = 1; i < argc; i += 2) {
        const char *a = argv[i], *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (!v) { usage(); return 2; }
        if (!strcmp(a, "--vk-lib")) lib = v;
        else if (!strcmp(a, "--scenes")) list = v;
        else if (!strcmp(a, "--seconds")) g.seconds = atof(v);
        else if (!strcmp(a, "--warmup")) g.warmup = atof(v);
        else if (!strcmp(a, "--width")) g.width = (uint32_t)atoi(v);
        else if (!strcmp(a, "--height")) g.height = (uint32_t)atoi(v);
        else if (!strcmp(a, "--preview")) g.preview_path = v;
        else if (!strcmp(a, "--preview-size")) {
            if (sscanf(v, "%ux%u", &g.preview_w, &g.preview_h) != 2) { usage(); return 2; }
        } else { usage(); return 2; }
    }
    if (g.width < 64 || g.height < 64 || g.width > 8192 || g.height > 8192 || g.preview_w < 8 || g.preview_h < 8 ||
        g.preview_w > 2048 || g.preview_h > 2048 || g.seconds <= 0 || g.warmup < 0) {
        usage();
        return 2;
    }
    g.times = calloc(MAX_FRAMES, sizeof(double));
    if (!g.times) return 2;

    int rc = init_vulkan(lib);
    if (rc) return rc;

    int status = 0;
    char ids[1024];
    snprintf(ids, sizeof(ids), "%s", list ? list : "");
    if (!list) {
        size_t n = 0;
        for (int s = 0; s < SCENE_COUNT; s++)
            n += (size_t)snprintf(ids + n, sizeof(ids) - n, "%s%s", s ? "," : "", scenes[s].id);
    }
    for (char *save = NULL, *id = strtok_r(ids, ",", &save); id; id = strtok_r(NULL, ",", &save)) {
        int s = 0;
        while (s < SCENE_COUNT && strcmp(scenes[s].id, id)) s++;
        if (s == SCENE_COUNT) {
            printf("DLBENCH error scene=%s msg=unknown scene\n", enc(id));
            status = 1;
            continue;
        }
        printf("DLBENCH start scene=%s\n", id);
        if (scenes[s].run(id)) status = 1;
        if (g.lost) {
            status = 3;
            break;
        }
    }
    printf("DLBENCH done\n");
    if (!g.lost) {
        vkDeviceWaitIdle(g.device);
        vkDestroyDevice(g.device, NULL);
        vkDestroyInstance(g.instance, NULL);
    }
    return status;
}
