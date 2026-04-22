/**
 * ggml-opencl.cpp — OpenCL GPU backend for ggml (Android / Mali / Adreno)
 *
 * Dynamically loads libOpenCL.so at runtime so no build-time SDK dependency
 * is required. Falls back to CPU silently if OpenCL is unavailable.
 *
 * Implements the ggml_cl_* API surface expected by ggml.c and llama.cpp when
 * compiled with GGML_USE_CLBLAST.
 *
 * Supported quantization types for GPU matmul:
 *   F32, F16, Q4_0, Q4_1, Q5_0, Q5_1, Q8_0, Q2_K, Q3_K, Q4_K, Q5_K, Q6_K
 */

#include "ggml-opencl.h"
#include "ggml.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <dlfcn.h>

#ifdef __ANDROID__
#include <android/log.h>
#define CL_LOG_TAG "ggml-opencl"
#define CL_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  CL_LOG_TAG, __VA_ARGS__)
#define CL_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  CL_LOG_TAG, __VA_ARGS__)
#define CL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CL_LOG_TAG, __VA_ARGS__)
#else
#define CL_LOGI(...) do { fprintf(stderr, "[ggml-opencl] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#define CL_LOGW(...) CL_LOGI(__VA_ARGS__)
#define CL_LOGE(...) CL_LOGI(__VA_ARGS__)
#endif

// ── Minimal OpenCL type definitions (no SDK headers needed) ──────────

typedef int32_t          cl_int;
typedef uint32_t         cl_uint;
typedef uint64_t         cl_ulong;
typedef cl_uint          cl_bool;
typedef cl_ulong         cl_bitfield;
typedef cl_bitfield      cl_device_type;
typedef cl_uint          cl_platform_info;
typedef cl_uint          cl_device_info;
typedef cl_uint          cl_context_info;
typedef cl_bitfield      cl_command_queue_properties;
typedef cl_uint          cl_program_build_info;
typedef cl_int           cl_build_status;
typedef cl_uint          cl_kernel_work_group_info;
typedef cl_bitfield      cl_mem_flags;
typedef cl_uint          cl_mem_info;
typedef cl_bitfield      cl_map_flags;

typedef struct _cl_platform_id *   cl_platform_id;
typedef struct _cl_device_id *     cl_device_id;
typedef struct _cl_context *       cl_context;
typedef struct _cl_command_queue *  cl_command_queue;
typedef struct _cl_mem *           cl_mem;
typedef struct _cl_program *       cl_program;
typedef struct _cl_kernel *        cl_kernel;
typedef struct _cl_event *         cl_event;

#define CL_SUCCESS                      0
#define CL_DEVICE_TYPE_GPU              (1 << 2)
#define CL_DEVICE_TYPE_ALL              0xFFFFFFFF
#define CL_PLATFORM_NAME                0x0902
#define CL_DEVICE_NAME                  0x102B
#define CL_DEVICE_TYPE                  0x1000
#define CL_DEVICE_MAX_COMPUTE_UNITS     0x1002
#define CL_DEVICE_MAX_WORK_GROUP_SIZE   0x1004
#define CL_DEVICE_GLOBAL_MEM_SIZE       0x101F
#define CL_DEVICE_LOCAL_MEM_SIZE        0x1023
#define CL_DEVICE_MAX_MEM_ALLOC_SIZE    0x1010
#define CL_CONTEXT_DEVICES              0x1081
#define CL_PROGRAM_BUILD_STATUS         0x1181
#define CL_PROGRAM_BUILD_LOG            0x1183
#define CL_BUILD_SUCCESS                0
#define CL_KERNEL_WORK_GROUP_SIZE       0x11B0
#define CL_MEM_READ_ONLY               (1 << 2)
#define CL_MEM_WRITE_ONLY              (1 << 1)
#define CL_MEM_READ_WRITE              (1 << 0)
#define CL_MEM_COPY_HOST_PTR           (1 << 5)
#define CL_MAP_READ                    (1 << 0)
#define CL_MAP_WRITE                   (1 << 1)
#define CL_TRUE                        1
#define CL_FALSE                       0
#define CL_MEM_SIZE                    0x1102

#ifndef CL_CALLBACK
#define CL_CALLBACK
#endif
typedef void (CL_CALLBACK * cl_context_callback)(const char *, const void *, size_t, void *);

// ── OpenCL function pointer types ───────────────────────────────────

#define CL_FUNC(ret, name, ...) typedef ret (* PFN_##name)(__VA_ARGS__)

CL_FUNC(cl_int, clGetPlatformIDs, cl_uint, cl_platform_id*, cl_uint*);
CL_FUNC(cl_int, clGetPlatformInfo, cl_platform_id, cl_platform_info, size_t, void*, size_t*);
CL_FUNC(cl_int, clGetDeviceIDs, cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
CL_FUNC(cl_int, clGetDeviceInfo, cl_device_id, cl_device_info, size_t, void*, size_t*);
CL_FUNC(cl_context, clCreateContext, const intptr_t*, cl_uint, const cl_device_id*, cl_context_callback, void*, cl_int*);
CL_FUNC(cl_int, clReleaseContext, cl_context);
CL_FUNC(cl_command_queue, clCreateCommandQueue, cl_context, cl_device_id, cl_command_queue_properties, cl_int*);
CL_FUNC(cl_int, clReleaseCommandQueue, cl_command_queue);
CL_FUNC(cl_mem, clCreateBuffer, cl_context, cl_mem_flags, size_t, void*, cl_int*);
CL_FUNC(cl_int, clReleaseMemObject, cl_mem);
CL_FUNC(cl_int, clEnqueueWriteBuffer, cl_command_queue, cl_mem, cl_bool, size_t, size_t, const void*, cl_uint, const cl_event*, cl_event*);
CL_FUNC(cl_int, clEnqueueReadBuffer, cl_command_queue, cl_mem, cl_bool, size_t, size_t, void*, cl_uint, const cl_event*, cl_event*);
CL_FUNC(cl_program, clCreateProgramWithSource, cl_context, cl_uint, const char**, const size_t*, cl_int*);
CL_FUNC(cl_int, clBuildProgram, cl_program, cl_uint, const cl_device_id*, const char*, void(*)(cl_program,void*), void*);
CL_FUNC(cl_int, clGetProgramBuildInfo, cl_program, cl_device_id, cl_program_build_info, size_t, void*, size_t*);
CL_FUNC(cl_int, clReleaseProgram, cl_program);
CL_FUNC(cl_kernel, clCreateKernel, cl_program, const char*, cl_int*);
CL_FUNC(cl_int, clReleaseKernel, cl_kernel);
CL_FUNC(cl_int, clSetKernelArg, cl_kernel, cl_uint, size_t, const void*);
CL_FUNC(cl_int, clEnqueueNDRangeKernel, cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, const cl_event*, cl_event*);
CL_FUNC(cl_int, clFinish, cl_command_queue);
CL_FUNC(cl_int, clGetKernelWorkGroupInfo, cl_kernel, cl_device_id, cl_kernel_work_group_info, size_t, void*, size_t*);

#undef CL_FUNC

// ── Dynamic loader ──────────────────────────────────────────────────

static void * cl_lib = nullptr;

#define LOAD_CL(name) \
    static PFN_##name p_##name = nullptr; \
    if (!p_##name && cl_lib) p_##name = (PFN_##name)dlsym(cl_lib, #name)

static bool load_opencl_lib() {
    if (cl_lib) return true;
    const char * paths[] = {
        "libOpenCL.so",
        "libOpenCL.so.1",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/egl/libGLES_mali.so",
        nullptr,
    };
    for (int i = 0; paths[i]; i++) {
        cl_lib = dlopen(paths[i], RTLD_LAZY);
        if (cl_lib) {
            CL_LOGI("Loaded OpenCL from %s", paths[i]);
            return true;
        }
    }
    CL_LOGW("OpenCL not available on this device");
    return false;
}

// ── Global state ────────────────────────────────────────────────────

static bool cl_initialized = false;
static bool cl_available = false;
static cl_platform_id cl_platform = nullptr;
static cl_device_id cl_device = nullptr;
static cl_context cl_ctx = nullptr;
static cl_command_queue cl_queue = nullptr;
static cl_program cl_prog = nullptr;
static size_t cl_max_wg_size = 256;
static std::mutex cl_mutex;

// Map tensor extra pointer -> cl_mem
static std::unordered_map<const void *, cl_mem> cl_buffers;

// ── OpenCL kernel source ────────────────────────────────────────────
//
// We implement dequantization + matmul for Q6_K (the user's model format)
// plus fallback dequant-to-f32-then-matmul for other types.

static const char * cl_kernel_source = R"CL(

// half → float helper (for devices without cl_khr_fp16)
inline float half_to_float(ushort h) {
    uint sign = (uint)(h >> 15) << 31;
    uint exp  = (h >> 10) & 0x1F;
    uint mant = h & 0x3FF;
    if (exp == 0) {
        if (mant == 0) return as_float(sign);
        // subnormal
        while (!(mant & 0x400)) { mant <<= 1; exp--; }
        exp++; mant &= ~0x400;
    } else if (exp == 31) {
        return as_float(sign | 0x7F800000 | (mant << 13));
    }
    return as_float(sign | ((exp + 112) << 23) | (mant << 13));
}

// ── Dequantize Q6_K ─────────────────────────────────────────────────
// block_q6_K: { ql[128], qh[64], scales[16], d(fp16) } = 210 bytes per 256 values
// Reference: dequantize_row_q6_K in ggml-quants.c
__kernel void dequantize_q6_K(
    __global const uchar * restrict src,
    __global float * restrict dst,
    const int k
) {
    const int block_size = 210;
    const int nb = k / 256;
    const int i = get_global_id(0);
    if (i >= nb) return;

    __global const uchar * block = src + i * block_size;
    // d is at the end (offset 208, 2 bytes fp16 LE)
    ushort d_half = (ushort)block[208] | ((ushort)block[209] << 8);
    float d = half_to_float(d_half);

    __global const uchar * ql_base = block;       // ql[128]
    __global const uchar * qh_base = block + 128; // qh[64]
    __global const char  * sc_base = (__global const char *)(block + 192); // scales[16]

    __global float * y = dst + i * 256;

    // Two passes of 128 values each
    for (int n = 0; n < 2; n++) {
        __global const uchar * ql = ql_base + n * 64;
        __global const uchar * qh = qh_base + n * 32;
        __global const char  * sc = sc_base  + n * 8;

        for (int l = 0; l < 32; ++l) {
            int is = l / 16;
            int q1 = (int)((ql[l]      & 0xF) | (((qh[l] >> 0) & 3) << 4)) - 32;
            int q2 = (int)((ql[l + 32] & 0xF) | (((qh[l] >> 2) & 3) << 4)) - 32;
            int q3 = (int)((ql[l]       >> 4) | (((qh[l] >> 4) & 3) << 4)) - 32;
            int q4 = (int)((ql[l + 32]  >> 4) | (((qh[l] >> 6) & 3) << 4)) - 32;
            y[l +  0] = d * (float)sc[is + 0] * (float)q1;
            y[l + 32] = d * (float)sc[is + 2] * (float)q2;
            y[l + 64] = d * (float)sc[is + 4] * (float)q3;
            y[l + 96] = d * (float)sc[is + 6] * (float)q4;
        }
        y += 128;
    }
}

// ── Dequantize Q4_0 ─────────────────────────────────────────────────
// block_q4_0: { d(fp16), qs[16] } = 18 bytes per 32 values
__kernel void dequantize_q4_0(
    __global const uchar * restrict src,
    __global float * restrict dst,
    const int k
) {
    const int qk = 32;
    const int nb = k / qk;
    const int gid = get_global_id(0);
    if (gid >= nb) return;

    __global const uchar * block = src + gid * 18;
    ushort d_half = (ushort)block[0] | ((ushort)block[1] << 8);
    float d = half_to_float(d_half);
    __global const uchar * qs = block + 2;
    __global float * out = dst + gid * 32;

    for (int j = 0; j < 16; j++) {
        int v0 = (int)(qs[j] & 0xF) - 8;
        int v1 = (int)(qs[j] >> 4) - 8;
        out[j]      = d * (float)v0;
        out[j + 16] = d * (float)v1;
    }
}

// ── Dequantize Q8_0 ─────────────────────────────────────────────────
// block_q8_0: { d(fp16), qs[32] } = 34 bytes per 32 values
__kernel void dequantize_q8_0(
    __global const uchar * restrict src,
    __global float * restrict dst,
    const int k
) {
    const int qk = 32;
    const int nb = k / qk;
    const int gid = get_global_id(0);
    if (gid >= nb) return;

    __global const uchar * block = src + gid * 34;
    ushort d_half = (ushort)block[0] | ((ushort)block[1] << 8);
    float d = half_to_float(d_half);
    __global const char * qs = (__global const char *)(block + 2);
    __global float * out = dst + gid * 32;

    for (int j = 0; j < 32; j++) {
        out[j] = d * (float)qs[j];
    }
}

// ── Matrix multiply: C = A * B^T  (A: MxK f32, B: NxK f32, C: MxN f32)
__kernel void mul_mat_f32(
    __global const float * restrict A,
    __global const float * restrict B,
    __global float * restrict C,
    const int M, const int N, const int K
) {
    const int row = get_global_id(0);
    const int col = get_global_id(1);
    if (row >= M || col >= N) return;

    float sum = 0.0f;
    for (int k = 0; k < K; k++) {
        sum += A[row * K + k] * B[col * K + k];
    }
    C[row * N + col] = sum;
}

// ── Element-wise multiply ────────────────────────────────────────────
__kernel void mul_f32(
    __global const float * restrict src0,
    __global const float * restrict src1,
    __global float * restrict dst,
    const int n
) {
    const int i = get_global_id(0);
    if (i >= n) return;
    dst[i] = src0[i] * src1[i];
}

)CL";

// ── Helper: build program ───────────────────────────────────────────

static bool build_kernels() {
    LOAD_CL(clCreateProgramWithSource);
    LOAD_CL(clBuildProgram);
    LOAD_CL(clGetProgramBuildInfo);

    if (!p_clCreateProgramWithSource || !p_clBuildProgram) return false;

    cl_int err;
    const char * src = cl_kernel_source;
    size_t len = strlen(src);
    cl_prog = p_clCreateProgramWithSource(cl_ctx, 1, &src, &len, &err);
    if (err != CL_SUCCESS || !cl_prog) {
        CL_LOGE("clCreateProgramWithSource failed: %d", err);
        return false;
    }

    err = p_clBuildProgram(cl_prog, 1, &cl_device, "-cl-std=CL1.2 -cl-mad-enable -cl-fast-relaxed-math", nullptr, nullptr);
    if (err != CL_SUCCESS) {
        if (p_clGetProgramBuildInfo) {
            size_t log_size = 0;
            p_clGetProgramBuildInfo(cl_prog, cl_device, CL_PROGRAM_BUILD_LOG, 0, nullptr, &log_size);
            if (log_size > 1) {
                std::vector<char> log(log_size);
                p_clGetProgramBuildInfo(cl_prog, cl_device, CL_PROGRAM_BUILD_LOG, log_size, log.data(), nullptr);
                CL_LOGE("OpenCL build log:\n%s", log.data());
            }
        }
        CL_LOGE("clBuildProgram failed: %d", err);
        return false;
    }

    CL_LOGI("OpenCL kernels compiled successfully");
    return true;
}

// ── Public API ──────────────────────────────────────────────────────

void ggml_cl_init(void) {
    std::lock_guard<std::mutex> lock(cl_mutex);
    if (cl_initialized) return;
    cl_initialized = true;

    if (!load_opencl_lib()) return;

    LOAD_CL(clGetPlatformIDs);
    LOAD_CL(clGetPlatformInfo);
    LOAD_CL(clGetDeviceIDs);
    LOAD_CL(clGetDeviceInfo);
    LOAD_CL(clCreateContext);
    LOAD_CL(clCreateCommandQueue);

    if (!p_clGetPlatformIDs || !p_clGetDeviceIDs || !p_clCreateContext || !p_clCreateCommandQueue) {
        CL_LOGE("Failed to load required OpenCL functions");
        return;
    }

    // Find a GPU platform/device
    cl_uint num_platforms = 0;
    p_clGetPlatformIDs(0, nullptr, &num_platforms);
    if (num_platforms == 0) {
        CL_LOGW("No OpenCL platforms found");
        return;
    }

    std::vector<cl_platform_id> platforms(num_platforms);
    p_clGetPlatformIDs(num_platforms, platforms.data(), nullptr);

    for (auto & plat : platforms) {
        cl_uint num_devices = 0;
        if (p_clGetDeviceIDs(plat, CL_DEVICE_TYPE_GPU, 0, nullptr, &num_devices) != CL_SUCCESS)
            continue;
        if (num_devices == 0) continue;

        std::vector<cl_device_id> devices(num_devices);
        p_clGetDeviceIDs(plat, CL_DEVICE_TYPE_GPU, num_devices, devices.data(), nullptr);

        cl_platform = plat;
        cl_device = devices[0];
        break;
    }

    if (!cl_device) {
        CL_LOGW("No OpenCL GPU device found");
        return;
    }

    // Log device info
    if (p_clGetPlatformInfo && p_clGetDeviceInfo) {
        char name[256] = {};
        p_clGetPlatformInfo(cl_platform, CL_PLATFORM_NAME, sizeof(name), name, nullptr);
        CL_LOGI("OpenCL platform: %s", name);
        p_clGetDeviceInfo(cl_device, CL_DEVICE_NAME, sizeof(name), name, nullptr);
        CL_LOGI("OpenCL device: %s", name);
        cl_ulong gmem = 0, lmem = 0;
        p_clGetDeviceInfo(cl_device, CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(gmem), &gmem, nullptr);
        p_clGetDeviceInfo(cl_device, CL_DEVICE_LOCAL_MEM_SIZE, sizeof(lmem), &lmem, nullptr);
        p_clGetDeviceInfo(cl_device, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(cl_max_wg_size), &cl_max_wg_size, nullptr);
        CL_LOGI("  Global mem: %llu MB, Local mem: %llu KB, Max WG: %zu",
                 (unsigned long long)(gmem / (1024*1024)),
                 (unsigned long long)(lmem / 1024),
                 cl_max_wg_size);
    }

    cl_int err;
    cl_ctx = p_clCreateContext(nullptr, 1, &cl_device, nullptr, nullptr, &err);
    if (err != CL_SUCCESS || !cl_ctx) {
        CL_LOGE("clCreateContext failed: %d", err);
        return;
    }

    cl_queue = p_clCreateCommandQueue(cl_ctx, cl_device, 0, &err);
    if (err != CL_SUCCESS || !cl_queue) {
        CL_LOGE("clCreateCommandQueue failed: %d", err);
        return;
    }

    if (!build_kernels()) {
        CL_LOGE("Failed to build OpenCL kernels");
        return;
    }

    cl_available = true;
    CL_LOGI("OpenCL GPU backend initialized successfully");
}

// ── Tensor → GPU buffer ─────────────────────────────────────────────

void ggml_cl_transform_tensor(void * data, struct ggml_tensor * tensor) {
    if (!cl_available) return;
    std::lock_guard<std::mutex> lock(cl_mutex);

    LOAD_CL(clCreateBuffer);
    if (!p_clCreateBuffer) return;

    const size_t size = ggml_nbytes(tensor);
    cl_int err;
    cl_mem buf = p_clCreateBuffer(cl_ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, size, data, &err);
    if (err != CL_SUCCESS || !buf) {
        CL_LOGE("clCreateBuffer failed for tensor %s: %d", tensor->name, err);
        return;
    }

    // Store mapping: tensor->extra -> cl_mem
    tensor->extra = (void *)(uintptr_t)1; // mark as uploaded
    cl_buffers[(const void *)tensor] = buf;
}

void ggml_cl_free_data(const struct ggml_tensor * tensor) {
    if (!cl_available) return;
    std::lock_guard<std::mutex> lock(cl_mutex);

    LOAD_CL(clReleaseMemObject);

    auto it = cl_buffers.find((const void *)tensor);
    if (it != cl_buffers.end()) {
        if (p_clReleaseMemObject) p_clReleaseMemObject(it->second);
        cl_buffers.erase(it);
    }
}

// ── Can we do this matmul on GPU? ───────────────────────────────────

bool ggml_cl_can_mul_mat(const struct ggml_tensor * src0, const struct ggml_tensor * src1, struct ggml_tensor * dst) {
    if (!cl_available) return false;
    if (src0->backend != GGML_BACKEND_GPU) return false;
    if (cl_buffers.find((const void *)src0) == cl_buffers.end()) return false;

    // We support these types for src0 (weights)
    switch (src0->type) {
        case GGML_TYPE_F32:
        case GGML_TYPE_F16:
        case GGML_TYPE_Q4_0:
        case GGML_TYPE_Q4_1:
        case GGML_TYPE_Q5_0:
        case GGML_TYPE_Q5_1:
        case GGML_TYPE_Q8_0:
        case GGML_TYPE_Q2_K:
        case GGML_TYPE_Q3_K:
        case GGML_TYPE_Q4_K:
        case GGML_TYPE_Q5_K:
        case GGML_TYPE_Q6_K:
            break;
        default:
            return false;
    }

    // src1 (activations) should be F32
    if (src1->type != GGML_TYPE_F32) return false;

    return true;
}

size_t ggml_cl_mul_mat_get_wsize(const struct ggml_tensor * src0, const struct ggml_tensor * src1, struct ggml_tensor * dst) {
    (void)dst;
    if (src0->type == GGML_TYPE_F32) return 0;
    // Need temp buffer for dequantized src0 rows
    return (size_t)src0->ne[0] * (size_t)src0->ne[1] * sizeof(float);
}

// ── Dequantize + matmul on GPU ──────────────────────────────────────

static cl_kernel get_kernel(const char * name) {
    LOAD_CL(clCreateKernel);
    if (!p_clCreateKernel || !cl_prog) return nullptr;
    cl_int err;
    cl_kernel k = p_clCreateKernel(cl_prog, name, &err);
    if (err != CL_SUCCESS) {
        CL_LOGE("clCreateKernel(%s) failed: %d", name, err);
        return nullptr;
    }
    return k;
}

static const char * dequant_kernel_name(enum ggml_type type) {
    switch (type) {
        case GGML_TYPE_Q4_0: return "dequantize_q4_0";
        case GGML_TYPE_Q8_0: return "dequantize_q8_0";
        case GGML_TYPE_Q6_K: return "dequantize_q6_K";
        default: return nullptr;
    }
}

void ggml_cl_mul_mat(const struct ggml_tensor * src0, const struct ggml_tensor * src1, struct ggml_tensor * dst, void * wdata, size_t wsize) {
    if (!cl_available) return;
    std::lock_guard<std::mutex> lock(cl_mutex);

    LOAD_CL(clCreateBuffer);
    LOAD_CL(clEnqueueWriteBuffer);
    LOAD_CL(clEnqueueReadBuffer);
    LOAD_CL(clSetKernelArg);
    LOAD_CL(clEnqueueNDRangeKernel);
    LOAD_CL(clFinish);
    LOAD_CL(clReleaseMemObject);
    LOAD_CL(clReleaseKernel);

    if (!p_clCreateBuffer || !p_clSetKernelArg || !p_clEnqueueNDRangeKernel || !p_clFinish) return;

    const int64_t ne00 = src0->ne[0]; // K (inner dim)
    const int64_t ne01 = src0->ne[1]; // N (rows of weight matrix)
    const int64_t ne10 = src1->ne[0]; // K
    const int64_t ne11 = src1->ne[1]; // M (rows of activation = batch)

    // C[M,N] = src1[M,K] × src0[N,K]^T
    const int M = (int)ne11;
    const int N = (int)ne01;
    const int K = (int)ne00;

    cl_int err;

    // Get src0 GPU buffer
    auto it = cl_buffers.find((const void *)src0);
    if (it == cl_buffers.end()) return;
    cl_mem buf_src0 = it->second;

    // If src0 needs dequantization, do it on GPU first
    cl_mem buf_src0_f32 = nullptr;
    bool need_dequant = (src0->type != GGML_TYPE_F32);
    const char * dq_name = need_dequant ? dequant_kernel_name(src0->type) : nullptr;

    if (need_dequant && dq_name) {
        // Dequantize on GPU
        size_t f32_size = (size_t)N * (size_t)K * sizeof(float);
        buf_src0_f32 = p_clCreateBuffer(cl_ctx, CL_MEM_READ_WRITE, f32_size, nullptr, &err);
        if (err != CL_SUCCESS) { CL_LOGE("Failed to alloc dequant buffer"); return; }

        cl_kernel dq_kernel = get_kernel(dq_name);
        if (dq_kernel) {
            int total_elements = N * K;
            p_clSetKernelArg(dq_kernel, 0, sizeof(cl_mem), &buf_src0);
            p_clSetKernelArg(dq_kernel, 1, sizeof(cl_mem), &buf_src0_f32);
            p_clSetKernelArg(dq_kernel, 2, sizeof(int), &total_elements);

            int qk = (src0->type == GGML_TYPE_Q6_K) ? 256 : 32;
            size_t num_blocks = (size_t)(total_elements / qk);
            size_t local = (cl_max_wg_size < num_blocks) ? cl_max_wg_size : num_blocks;
            if (local == 0) local = 1;
            size_t global = ((num_blocks + local - 1) / local) * local;

            err = p_clEnqueueNDRangeKernel(cl_queue, dq_kernel, 1, nullptr, &global, &local, 0, nullptr, nullptr);
            if (err != CL_SUCCESS) CL_LOGE("Dequant kernel failed: %d", err);
            if (p_clReleaseKernel) p_clReleaseKernel(dq_kernel);
        }
    } else if (need_dequant) {
        // No GPU kernel for this type — dequantize on CPU, upload
        size_t f32_size = (size_t)N * (size_t)K * sizeof(float);
        float * tmp = (float *)wdata;
        if (!tmp || wsize < f32_size) return;

        // Use ggml's dequantize
        ggml_type_traits_t traits = ggml_internal_get_type_traits(src0->type);
        if (traits.to_float) {
            traits.to_float(src0->data, tmp, N * K);
        } else {
            return;
        }

        buf_src0_f32 = p_clCreateBuffer(cl_ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, f32_size, tmp, &err);
        if (err != CL_SUCCESS) return;
    }

    cl_mem effective_src0 = need_dequant ? buf_src0_f32 : buf_src0;

    // Upload src1 (activations, always f32)
    size_t src1_size = (size_t)M * (size_t)K * sizeof(float);
    cl_mem buf_src1 = p_clCreateBuffer(cl_ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, src1_size, src1->data, &err);
    if (err != CL_SUCCESS) {
        if (buf_src0_f32 && p_clReleaseMemObject) p_clReleaseMemObject(buf_src0_f32);
        return;
    }

    // Output buffer
    size_t dst_size = (size_t)M * (size_t)N * sizeof(float);
    cl_mem buf_dst = p_clCreateBuffer(cl_ctx, CL_MEM_WRITE_ONLY, dst_size, nullptr, &err);
    if (err != CL_SUCCESS) {
        if (p_clReleaseMemObject) p_clReleaseMemObject(buf_src1);
        if (buf_src0_f32 && p_clReleaseMemObject) p_clReleaseMemObject(buf_src0_f32);
        return;
    }

    // Run matmul kernel
    cl_kernel mm_kernel = get_kernel("mul_mat_f32");
    if (mm_kernel) {
        p_clSetKernelArg(mm_kernel, 0, sizeof(cl_mem), &buf_src1);
        p_clSetKernelArg(mm_kernel, 1, sizeof(cl_mem), &effective_src0);
        p_clSetKernelArg(mm_kernel, 2, sizeof(cl_mem), &buf_dst);
        p_clSetKernelArg(mm_kernel, 3, sizeof(int), &M);
        p_clSetKernelArg(mm_kernel, 4, sizeof(int), &N);
        p_clSetKernelArg(mm_kernel, 5, sizeof(int), &K);

        size_t global[2] = { (size_t)M, (size_t)N };
        err = p_clEnqueueNDRangeKernel(cl_queue, mm_kernel, 2, nullptr, global, nullptr, 0, nullptr, nullptr);
        if (err != CL_SUCCESS) {
            CL_LOGE("mul_mat_f32 kernel failed: %d", err);
        }
        if (p_clReleaseKernel) p_clReleaseKernel(mm_kernel);
    }

    // Read result
    if (p_clEnqueueReadBuffer) {
        p_clEnqueueReadBuffer(cl_queue, buf_dst, CL_TRUE, 0, dst_size, dst->data, 0, nullptr, nullptr);
    }
    p_clFinish(cl_queue);

    // Cleanup temporary buffers
    if (p_clReleaseMemObject) {
        p_clReleaseMemObject(buf_src1);
        p_clReleaseMemObject(buf_dst);
        if (buf_src0_f32) p_clReleaseMemObject(buf_src0_f32);
    }
}

// ── Element-wise multiply ───────────────────────────────────────────
// Handles broadcasting: src0=[ne00,ne01,...], src1=[ne00] (1D weight on GPU).
// Downloads src1 from GPU and does the multiply on CPU for correctness
// (this is a tiny op relative to matmul and not a performance bottleneck).

void ggml_cl_mul(const struct ggml_tensor * src0, const struct ggml_tensor * src1, struct ggml_tensor * dst) {
    if (!cl_available) return;
    std::lock_guard<std::mutex> lock(cl_mutex);

    LOAD_CL(clEnqueueReadBuffer);
    LOAD_CL(clFinish);

    auto it = cl_buffers.find((const void *)src1);
    if (it == cl_buffers.end()) return;

    const int64_t ne00 = src0->ne[0]; // inner dim
    const int64_t ne01 = src0->ne[1]; // rows of src0
    const int64_t ne10 = src1->ne[0]; // inner dim of src1

    // Download src1 from GPU
    size_t s1_bytes = ggml_nbytes(src1);
    std::vector<float> src1_data(ggml_nelements(src1));
    if (p_clEnqueueReadBuffer && p_clFinish) {
        p_clEnqueueReadBuffer(cl_queue, it->second, CL_TRUE, 0, s1_bytes, src1_data.data(), 0, nullptr, nullptr);
        p_clFinish(cl_queue);
    } else {
        return;
    }

    const float * s0 = (const float *)src0->data;
    float * d = (float *)dst->data;
    const int64_t nr = ggml_nrows(src0);

    for (int64_t r = 0; r < nr; r++) {
        for (int64_t i = 0; i < ne00; i++) {
            // Broadcast src1 across rows: use i % ne10 for safety
            d[r * ne00 + i] = s0[r * ne00 + i] * src1_data[i % ne10];
        }
    }
}
