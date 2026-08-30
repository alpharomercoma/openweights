// The CPU half of the NPU comparison, at exactly the same shape.
//
// The NPU harness reports GOP/s for [M x 2048] @ [2048 x 8192]. The figure it
// was compared against was derived from end-to-end prefill, so it carried
// attention, norms and softmax that this matmul does not. This measures the
// same multiply on the same phone through ggml, so the two numbers are the same
// kernel and the comparison means something.
//
// Both quantisations are run. Q4_K_M is what the app actually ships and what
// the CPU is good at; Q8_0 is the width the NPU is restricted to, and is the
// like-for-like row.
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <vector>

namespace {

int64_t kIn  = 2048;
int64_t kOut = 8192;
constexpr int kIterations = 20;
constexpr int kThreads = 6;

double MillisSince(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(
               std::chrono::steady_clock::now() - start).count();
}

// Set through the registry rather than by linking ggml_backend_cpu_set_n_threads,
// because this build has GGML_BACKEND_DL on: the CPU kernels live in loadable
// per-architecture modules, and the point of measuring through the registry is
// that it selects the very same armv9.0 + KleidiAI variant the app runs on this
// phone. Linking a private CPU build would measure kernels the app never uses.
typedef void (*set_n_threads_fn)(ggml_backend_t, int);

void Run(ggml_type weightType, const char* label, int64_t tokens) {
    ggml_backend_t backend = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    if (backend == nullptr) {
        std::printf("no CPU backend registered\n");
        return;
    }
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(ggml_backend_get_device(backend));
    auto setThreads = (set_n_threads_fn)
        ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads");
    if (setThreads != nullptr) setThreads(backend, kThreads);

    ggml_init_params params{};
    params.mem_size = ggml_tensor_overhead() * 8 + ggml_graph_overhead();
    params.no_alloc = true;
    ggml_context* ctx = ggml_init(params);

    // ggml_mul_mat(A, B): A is [k, n] and B is [k, m], giving [n, m].
    ggml_tensor* weights = ggml_new_tensor_2d(ctx, weightType, kIn, kOut);
    ggml_tensor* activations = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, kIn, tokens);
    ggml_tensor* out = ggml_mul_mat(ctx, weights, activations);

    // Weights must live on the backend's EXTRA buffer type when there is one.
    // KleidiAI registers its own, and its set_tensor repacks the weights into
    // the blocked layout its kernels need; allocating on the default CPU buffer
    // silently gets the generic path instead. llama.cpp does exactly this via
    // ggml_backend_dev_get_extra_bufts, and a harness that skips it measures a
    // slower CPU than the app actually has — which inflates any accelerator it
    // is being compared against.
    ggml_backend_dev_t dev = ggml_backend_get_device(backend);
    ggml_backend_reg_t devreg = ggml_backend_dev_backend_reg(dev);
    auto get_extra = (ggml_backend_dev_get_extra_bufts_t)
        ggml_backend_reg_get_proc_address(devreg, "ggml_backend_dev_get_extra_bufts");
    ggml_backend_buffer_type_t weight_buft = nullptr;
    if (get_extra) {
        // The first extra type is the accelerated one (KleidiAI here); llama.cpp
        // walks the same list and takes the first that fits the tensor.
        // Only where the accelerated buffer can actually hold this type: KleidiAI
        // repacks Q4_0, Q8_0 and F32 and asserts on anything else, so Q4_K, F16
        // and BF16 must stay on the default buffer. That asymmetry is the finding,
        // not a nuisance: it is exactly why Q4_K never sees an accelerated kernel.
        const bool kleidi_ok = (weightType == GGML_TYPE_Q4_0 ||
                                weightType == GGML_TYPE_Q8_0 ||
                                weightType == GGML_TYPE_F32);
        ggml_backend_buffer_type_t * p = get_extra(dev);
        if (kleidi_ok && p && *p) weight_buft = *p;
    }
    const char * buft_name = weight_buft ? ggml_backend_buft_name(weight_buft) : "default";
    ggml_backend_buffer_t buffer = weight_buft
        ? ggml_backend_alloc_ctx_tensors_from_buft(ctx, weight_buft)
        : ggml_backend_alloc_ctx_tensors(ctx, backend);
    if (buffer == nullptr) {
        std::printf("%s M=%lld: allocation failed\n", label, (long long) tokens);
        ggml_free(ctx);
        ggml_backend_free(backend);
        return;
    }

    // Contents are irrelevant to timing, but the buffers must be initialised or
    // denormals could make this measure something other than the kernel.
    std::vector<uint8_t> weightBytes(ggml_nbytes(weights), 0x11);
    ggml_backend_tensor_set(weights, weightBytes.data(), 0, weightBytes.size());
    std::vector<float> activationValues(kIn * tokens, 0.05f);
    ggml_backend_tensor_set(activations, activationValues.data(), 0,
                            activationValues.size() * sizeof(float));

    ggml_cgraph* graph = ggml_new_graph(ctx);
    ggml_build_forward_expand(graph, out);

    ggml_backend_graph_compute(backend, graph);  // warm up

    std::vector<double> samples;
    samples.reserve(kIterations);
    for (int i = 0; i < kIterations; ++i) {
        auto start = std::chrono::steady_clock::now();
        ggml_backend_graph_compute(backend, graph);
        samples.push_back(MillisSince(start));
    }
    std::sort(samples.begin(), samples.end());

    const double best = samples.front();
    const double median = samples[samples.size() / 2];
    const double ops = 2.0 * tokens * kIn * kOut;
    std::printf("RESULT cpu %s M=%lld K=%lld N=%lld best_ms=%.4f median_ms=%.4f "
                "best_gops=%.1f median_gops=%.1f buft=%s\n",
                label, (long long) tokens, (long long) kIn, (long long) kOut,
                best, median, ops / (best * 1e6), ops / (median * 1e6), buft_name);

    ggml_backend_buffer_free(buffer);
    ggml_free(ctx);
    ggml_backend_free(backend);
}

}  // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    ggml_backend_load_all();
    // argv: M K N  — one shape per invocation, so a driver can walk the real
    // matmul mix of an actual model.
    int64_t tokens = 128;
    if (argc >= 4) {
        tokens = std::atoll(argv[1]);
        kIn    = std::atoll(argv[2]);
        kOut   = std::atoll(argv[3]);
    }
    Run(GGML_TYPE_Q4_0, "Q4_0", tokens);
    Run(GGML_TYPE_Q4_K, "Q4_K", tokens);
    Run(GGML_TYPE_Q8_0, "Q8_0", tokens);
    Run(GGML_TYPE_F16,  "F16",  tokens);
    Run(GGML_TYPE_BF16, "BF16", tokens);
    return 0;
}
